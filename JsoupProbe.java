import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

public class JsoupProbe {
  public static void main(String[] args) throws Exception {
    try {
      Document doc = Jsoup.connect("https://www.screener.in/company/RELIANCE/")
        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
        .header("Accept-Language", "en-US,en;q=0.9")
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Referer", "https://www.screener.in/")
        .followRedirects(true)
        .timeout(20000)
        .get();
      System.out.println("TITLE=" + doc.title());
      System.out.println("H1=" + doc.selectFirst("h1").text());
    } catch (Exception e) {
      System.out.println("EX=" + e.getClass().getName());
      System.out.println("MSG=" + e.getMessage());
      Throwable c = e.getCause();
      if (c != null) {
        System.out.println("CAUSE=" + c.getClass().getName());
        System.out.println("CAUSEMSG=" + c.getMessage());
      }
    }
  }
}
