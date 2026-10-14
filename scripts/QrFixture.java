// Test-only image conversion: Vue-generated QR GIF -> camera-equivalent JPEG.
import javax.imageio.ImageIO;
import java.io.File;
import java.awt.image.BufferedImage;
public class QrFixture {
 public static void main(String[] args) throws Exception {
  var source=ImageIO.read(new File(args[0]));var image=new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();graphics.drawImage(source,0,0,null);graphics.dispose();ImageIO.write(image,"jpeg",new File(args[1]));
 }
}
