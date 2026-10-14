=== FILE: src/main/java/dev/shortener/Base62.java ===
package dev.shortener;
public final class Base62 {
  private static final String A = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
  public static String encode(long n) { StringBuilder s = new StringBuilder(); do { s.append(A.charAt((int) (n % 62))); n /= 62; } while (n > 0); return s.reverse().toString(); }
}
=== FILE: src/main/java/dev/shortener/LinkService.java ===
package dev.shortener;
public class LinkService { /* create, resolve with expiry check */ }