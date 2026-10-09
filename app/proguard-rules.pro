# kotlinx.serialization keeps its own rules via the library; nothing app-specific needed.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# jsoup references optional re2j for regex selectors.
-dontwarn com.google.re2j.**
