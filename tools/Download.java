import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

public final class Download {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: Download URL OUTPUT");
        }
        var client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();
        var request = HttpRequest.newBuilder(URI.create(args[0]))
            .header("User-Agent", "HiFuture-privacy-patch")
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofFile(Path.of(args[1])));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("download failed: HTTP " + response.statusCode());
        }
    }
}
