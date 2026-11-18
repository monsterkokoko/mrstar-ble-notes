package ua.light.blebridge;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One photo on request, for checking what the lamps really look like (colour correction of the strip).
 *
 * The camera runs a short preview into a throwaway YUV reader so exposure and focus settle, then takes one JPEG.
 * White balance is fixed (daylight unless asked otherwise): with auto white balance the phone would "correct" a room
 * lit red back towards grey, and the photos would lie about the colour. Exposure is either automatic with a
 * compensation (?ev=-2), locked before the shot, or fully manual (?exposure_ms=&iso=) where the camera allows it,
 * so a series of shots can share one exposure.
 *
 * GET /camera — what the cameras can do. GET /photo?cam=&w=&wb=&ev=&exposure_ms=&iso=&settle_ms=&zoom= — a JPEG,
 * with what was used in the X-Shot header (JSON).
 */
final class Cam {
    static final class Shot {
        final byte[] jpeg;
        final JSONObject meta;

        Shot(byte[] jpeg, JSONObject meta) {
            this.jpeg = jpeg;
            this.meta = meta;
        }
    }

    private final Context ctx;
    private final Handler handler;

    Cam(Context ctx) {
        this.ctx = ctx;
        HandlerThread t = new HandlerThread("camera");
        t.start();
        handler = new Handler(t.getLooper());
    }

    private CameraManager manager() {
        return (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
    }

    private static boolean manualSensor(CameraCharacteristics ch) {
        int[] caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (caps == null) return false;
        for (int c : caps) if (c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) return true;
        return false;
    }

    private static String level(Integer l) {
        if (l == null) return "?";
        switch (l) {
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: return "legacy";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: return "limited";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL: return "full";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3: return "level3";
            default: return String.valueOf(l);
        }
    }

    synchronized JSONArray info() throws Exception {
        JSONArray out = new JSONArray();
        CameraManager cm = manager();
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            JSONObject o = new JSONObject();
            o.put("id", id);
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            o.put("facing", facing == null ? "?" : facing == CameraCharacteristics.LENS_FACING_BACK ? "back"
                    : facing == CameraCharacteristics.LENS_FACING_FRONT ? "front" : "external");
            o.put("level", level(ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)));
            o.put("manual_sensor", manualSensor(ch));
            Range<Integer> ae = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            if (ae != null) o.put("ev_range", ae.toString());
            o.put("ev_step", String.valueOf(ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)));
            Range<Long> exp = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            if (exp != null) o.put("exposure_ns", exp.toString());
            Range<Integer> iso = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (iso != null) o.put("iso", iso.toString());
            int[] awb = ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
            if (awb != null) o.put("awb_modes", Arrays.toString(awb));
            Float zoom = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            if (zoom != null) o.put("max_zoom", zoom);
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) o.put("jpeg_sizes", Arrays.toString(map.getOutputSizes(ImageFormat.JPEG)));
            out.put(o);
        }
        return out;
    }

    private static int awbMode(String wb) {
        if (wb == null || wb.isEmpty() || wb.equals("daylight")) return CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT;
        switch (wb) {
            case "auto": return CaptureRequest.CONTROL_AWB_MODE_AUTO;
            case "cloudy": return CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT;
            case "incandescent": return CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT;
            case "fluorescent": return CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT;
            case "shade": return CaptureRequest.CONTROL_AWB_MODE_SHADE;
            default: throw new IllegalArgumentException("wb: daylight|auto|cloudy|incandescent|fluorescent|shade");
        }
    }

    /** The largest size of the format no wider than maxW (or the smallest one, if all are wider). */
    private static Size pick(Size[] sizes, int maxW) {
        Size best = null, smallest = null;
        for (Size s : sizes) {
            if (smallest == null || s.getWidth() < smallest.getWidth()) smallest = s;
            if (s.getWidth() <= maxW && (best == null || s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight())) best = s;
        }
        return best != null ? best : smallest;
    }

    private static int intParam(Uri u, String k, int def) {
        String v = u.getQueryParameter(k);
        return v == null || v.isEmpty() ? def : Integer.parseInt(v);
    }

    private static Double numParam(Uri u, String k) {
        String v = u.getQueryParameter(k);
        return v == null || v.isEmpty() ? null : Double.valueOf(v);
    }

    synchronized Shot shoot(Uri u) throws Exception {
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            throw new IllegalStateException("no camera permission: open BLE Bridge and allow the camera");
        CameraManager cm = manager();
        String id = u.getQueryParameter("cam");
        if (id == null) {
            for (String c : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == CameraCharacteristics.LENS_FACING_BACK) { id = c; break; }
            }
            if (id == null) id = cm.getCameraIdList()[0];
        }
        CameraCharacteristics ch = cm.getCameraCharacteristics(id);
        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size jpegSize = pick(map.getOutputSizes(ImageFormat.JPEG), intParam(u, "w", 1600));
        Size prevSize = pick(map.getOutputSizes(ImageFormat.YUV_420_888), 640);

        final int awb = awbMode(u.getQueryParameter("wb"));
        final Double expMs = numParam(u, "exposure_ms");
        final Integer iso = u.getQueryParameter("iso") == null ? null : intParam(u, "iso", 100);
        final boolean manual = expMs != null && iso != null;
        if (manual && !manualSensor(ch)) throw new IllegalArgumentException("this camera has no manual exposure");
        final int ev = intParam(u, "ev", 0);
        final int settle = Math.max(300, Math.min(8000, intParam(u, "settle_ms", 1500)));
        Double zoom = numParam(u, "zoom");

        final ImageReader preview = ImageReader.newInstance(prevSize.getWidth(), prevSize.getHeight(), ImageFormat.YUV_420_888, 3);
        final ImageReader still = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2);
        final BlockingQueue<byte[]> jpegs = new ArrayBlockingQueue<>(2);
        preview.setOnImageAvailableListener(r -> {
            Image img = r.acquireLatestImage();
            if (img != null) img.close();
        }, handler);
        still.setOnImageAvailableListener(r -> {
            Image img = r.acquireLatestImage();
            if (img == null) return;
            ByteBuffer b = img.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[b.remaining()];
            b.get(bytes);
            img.close();
            jpegs.offer(bytes);
        }, handler);

        CameraDevice cam = null;
        CameraCaptureSession session = null;
        try {
            cam = open(cm, id);
            session = configure(cam, preview.getSurface(), still.getSurface());

            final AtomicReference<TotalCaptureResult> last = new AtomicReference<>();
            CameraCaptureSession.CaptureCallback track = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                    last.set(res);
                }
            };

            CaptureRequest.Builder pb = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            pb.addTarget(preview.getSurface());
            setup(pb, ch, awb, manual, expMs, iso, ev, zoom);
            session.setRepeatingRequest(pb.build(), track, handler);
            Thread.sleep(settle);

            // Hold what auto exposure found, so the still is taken with it.
            if (!manual) pb.set(CaptureRequest.CONTROL_AE_LOCK, true);
            pb.set(CaptureRequest.CONTROL_AWB_LOCK, true);
            session.setRepeatingRequest(pb.build(), track, handler);
            Thread.sleep(400);

            CaptureRequest.Builder sb = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            sb.addTarget(still.getSurface());
            setup(sb, ch, awb, manual, expMs, iso, ev, zoom);
            if (!manual) sb.set(CaptureRequest.CONTROL_AE_LOCK, true);
            sb.set(CaptureRequest.CONTROL_AWB_LOCK, true);
            sb.set(CaptureRequest.JPEG_QUALITY, (byte) 92);
            sb.set(CaptureRequest.JPEG_ORIENTATION, 0);
            final AtomicReference<TotalCaptureResult> shotResult = new AtomicReference<>();
            session.capture(sb.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                    shotResult.set(res);
                }
            }, handler);
            byte[] jpeg = jpegs.poll(8, TimeUnit.SECONDS);
            if (jpeg == null) throw new IllegalStateException("the camera gave no picture");
            Thread.sleep(100);  // the result usually lands with the picture; give it a moment

            TotalCaptureResult res = shotResult.get() != null ? shotResult.get() : last.get();
            JSONObject meta = new JSONObject();
            meta.put("cam", id);
            meta.put("size", jpegSize.toString());
            meta.put("wb", u.getQueryParameter("wb") == null ? "daylight" : u.getQueryParameter("wb"));
            meta.put("manual", manual);
            meta.put("ev", ev);
            if (res != null) {
                Long e = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                if (e != null) meta.put("exposure_ms", e / 1e6);
                Integer s = res.get(CaptureResult.SENSOR_SENSITIVITY);
                if (s != null) meta.put("iso", s);
                Integer aeState = res.get(CaptureResult.CONTROL_AE_STATE);
                if (aeState != null) meta.put("ae_state", aeState);
                RggbChannelVector g = res.get(CaptureResult.COLOR_CORRECTION_GAINS);
                if (g != null) meta.put("wb_gains", g.getRed() + "," + g.getGreenEven() + "," + g.getGreenOdd() + "," + g.getBlue());
            }
            return new Shot(jpeg, meta);
        } finally {
            if (session != null) try { session.close(); } catch (Exception ignored) { }
            if (cam != null) try { cam.close(); } catch (Exception ignored) { }
            preview.close();
            still.close();
        }
    }

    /**
     * A run of frames from the preview stream, for filming the strip's effects: exposure and white balance settle and
     * lock first, then every frame for ms milliseconds goes out as a JPEG. The body is the JPEGs back to back; the
     * X-Frames header (JSON) says where each starts, how long it is and when it was taken (ms from the first).
     * GET /burst?w=640&ms=4000&ev=-2&wb=daylight&settle_ms=1500&q=75&zoom=
     */
    synchronized Shot burst(Uri u) throws Exception {
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            throw new IllegalStateException("no camera permission: open BLE Bridge and allow the camera");
        CameraManager cm = manager();
        String id = u.getQueryParameter("cam");
        if (id == null) {
            for (String c : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == CameraCharacteristics.LENS_FACING_BACK) { id = c; break; }
            }
            if (id == null) id = cm.getCameraIdList()[0];
        }
        CameraCharacteristics ch = cm.getCameraCharacteristics(id);
        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size size = pick(map.getOutputSizes(ImageFormat.YUV_420_888), intParam(u, "w", 640));
        final int awb = awbMode(u.getQueryParameter("wb"));
        final int ev = intParam(u, "ev", 0);
        final int settle = Math.max(300, Math.min(8000, intParam(u, "settle_ms", 1500)));
        final int ms = Math.max(200, Math.min(15000, intParam(u, "ms", 4000)));
        final int quality = Math.max(30, Math.min(95, intParam(u, "q", 75)));
        Double zoom = numParam(u, "zoom");

        // The fastest steady frame rate the camera offers, so motion is caught.
        Range<Integer> fps = null;
        Range<Integer>[] ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges != null) for (Range<Integer> r : ranges) {
            if (fps == null || r.getLower() > fps.getLower()
                    || (r.getLower().equals(fps.getLower()) && r.getUpper() > fps.getUpper())) fps = r;
        }

        final java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        final JSONArray frames = new JSONArray();
        final long[] t0 = {-1};
        final boolean[] recording = {false};
        final int W = size.getWidth(), H = size.getHeight();
        final byte[] nv21 = new byte[W * H * 3 / 2];
        final ImageReader reader = ImageReader.newInstance(W, H, ImageFormat.YUV_420_888, 4);
        reader.setOnImageAvailableListener(r -> {
            Image img = r.acquireLatestImage();
            if (img == null) return;
            try {
                if (!recording[0] || frames.length() >= 400) return;
                long ts = img.getTimestamp();
                toNv21(img, nv21, W, H);
                android.graphics.YuvImage yuv = new android.graphics.YuvImage(nv21, ImageFormat.NV21, W, H, null);
                int start = body.size();
                yuv.compressToJpeg(new android.graphics.Rect(0, 0, W, H), quality, body);
                if (t0[0] < 0) t0[0] = ts;
                JSONArray f = new JSONArray();
                f.put(start);
                f.put(body.size() - start);
                f.put((ts - t0[0]) / 1_000_000L);
                frames.put(f);
            } catch (Exception e) {
                Ble.log("burst frame: " + e);
            } finally {
                img.close();
            }
        }, handler);

        CameraDevice cam = null;
        CameraCaptureSession session = null;
        try {
            cam = open(cm, id);
            session = configure(cam, reader.getSurface());
            CaptureRequest.Builder pb = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            pb.addTarget(reader.getSurface());
            setup(pb, ch, awb, false, null, null, ev, zoom);
            if (fps != null) pb.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
            session.setRepeatingRequest(pb.build(), null, handler);
            Thread.sleep(settle);
            pb.set(CaptureRequest.CONTROL_AE_LOCK, true);
            pb.set(CaptureRequest.CONTROL_AWB_LOCK, true);
            session.setRepeatingRequest(pb.build(), null, handler);
            Thread.sleep(300);
            final CountDownLatch go = new CountDownLatch(1);
            handler.post(() -> { recording[0] = true; go.countDown(); });
            go.await(2, TimeUnit.SECONDS);
            Thread.sleep(ms);
            final CountDownLatch stop = new CountDownLatch(1);
            handler.post(() -> { recording[0] = false; stop.countDown(); });
            stop.await(2, TimeUnit.SECONDS);

            JSONObject meta = new JSONObject();
            meta.put("cam", id);
            meta.put("size", size.toString());
            meta.put("fps_range", fps == null ? "?" : fps.toString());
            meta.put("frames", frames);
            return new Shot(body.toByteArray(), meta);
        } finally {
            if (session != null) try { session.close(); } catch (Exception ignored) { }
            if (cam != null) try { cam.close(); } catch (Exception ignored) { }
            reader.close();
        }
    }

    /** YUV_420_888, whatever its strides, into NV21 (Y plane, then V and U interleaved) for YuvImage. */
    private static void toNv21(Image img, byte[] out, int w, int h) {
        Image.Plane[] p = img.getPlanes();
        ByteBuffer y = p[0].getBuffer();
        int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        int o = 0;
        for (int r = 0; r < h; r++) {
            int base = r * yRow;
            if (yPix == 1) {
                y.position(base);
                y.get(out, o, w);
                o += w;
            } else {
                for (int c = 0; c < w; c++) out[o++] = y.get(base + c * yPix);
            }
        }
        ByteBuffer ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride();
        int vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();
        for (int r = 0; r < h / 2; r++) {
            for (int c = 0; c < w / 2; c++) {
                out[o++] = vb.get(r * vRow + c * vPix);
                out[o++] = ub.get(r * uRow + c * uPix);
            }
        }
    }

    private static void setup(CaptureRequest.Builder b, CameraCharacteristics ch, int awb, boolean manual,
                              Double expMs, Integer iso, int ev, Double zoom) {
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AWB_MODE, awb);
        int[] af = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (af != null) for (int m : af) if (m == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) {
            b.set(CaptureRequest.CONTROL_AF_MODE, m);
            break;
        }
        if (manual) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            long ns = Math.round(expMs * 1e6);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(ns, 33_333_333L));
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            Range<Integer> r = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            if (r != null) b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, r.clamp(ev));
        }
        if (zoom != null && zoom > 1) {
            android.graphics.Rect a = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Float max = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            double z = Math.min(zoom, max == null ? 1 : max);
            int w = (int) (a.width() / z), h = (int) (a.height() / z);
            int x = (a.width() - w) / 2, y = (a.height() - h) / 2;
            b.set(CaptureRequest.SCALER_CROP_REGION, new android.graphics.Rect(x, y, x + w, y + h));
        }
    }

    private CameraDevice open(CameraManager cm, String id) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<CameraDevice> dev = new AtomicReference<>();
        final AtomicReference<String> err = new AtomicReference<>();
        try {
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice c) {
                    dev.set(c);
                    done.countDown();
                }

                @Override
                public void onDisconnected(CameraDevice c) {
                    c.close();
                    err.set("disconnected (another app has the camera?)");
                    done.countDown();
                }

                @Override
                public void onError(CameraDevice c, int error) {
                    c.close();
                    err.set("error " + error);
                    done.countDown();
                }
            }, handler);
        } catch (SecurityException e) {
            throw new IllegalStateException("no camera permission");
        }
        if (!done.await(6, TimeUnit.SECONDS)) throw new IllegalStateException("camera did not open");
        if (dev.get() == null) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "camera: " + err.get());
        return dev.get();
    }

    private CameraCaptureSession configure(CameraDevice cam, Surface... surfaces) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<CameraCaptureSession> ses = new AtomicReference<>();
        cam.createCaptureSession(Arrays.asList(surfaces), new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession s) {
                ses.set(s);
                done.countDown();
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession s) {
                done.countDown();
            }
        }, handler);
        if (!done.await(6, TimeUnit.SECONDS) || ses.get() == null)
            throw new IllegalStateException("camera session failed");
        return ses.get();
    }
}
