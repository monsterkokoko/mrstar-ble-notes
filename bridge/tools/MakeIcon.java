import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/** Draws the launcher icon as plain PNGs (the MIUI installer cannot show vector app icons). */
public class MakeIcon {
    public static void main(String[] args) throws Exception {
        String[] dirs = {"mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"};
        int[] px = {48, 72, 96, 144, 192};
        for (int i = 0; i < dirs.length; i++) {
            File dir = new File(args[0], dirs[i]);
            dir.mkdirs();
            ImageIO.write(draw(px[i]), "png", new File(dir, "ic_launcher.png"));
        }
    }

    static BufferedImage draw(int n) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        float s = n / 48f;
        g.setPaint(new GradientPaint(0, 0, new Color(0x2b2f6b), n, n, new Color(0x7a2fbf)));
        g.fill(new RoundRectangle2D.Float(2 * s, 2 * s, 44 * s, 44 * s, 14 * s, 14 * s));

        // Bluetooth rune
        Path2D p = new Path2D.Float();
        p.moveTo(16 * s, 17 * s);
        p.lineTo(31 * s, 30 * s);
        p.lineTo(24 * s, 36 * s);
        p.lineTo(24 * s, 12 * s);
        p.lineTo(31 * s, 18 * s);
        p.lineTo(16 * s, 31 * s);
        g.setColor(new Color(0xfff2b0));
        g.setStroke(new BasicStroke(3.2f * s, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(p);
        g.dispose();
        return img;
    }
}
