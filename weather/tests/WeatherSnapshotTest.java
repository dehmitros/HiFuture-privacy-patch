import org.hifuture.weather.WeatherSnapshot;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.http.*;

public final class WeatherSnapshotTest {
    private static int checks;
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("Check " + checks); }
    private static void rejected(RunnableWithException fn) throws Exception {
        checks++;
        try { fn.run(); } catch (Exception expected) { return; }
        throw new AssertionError("Expected rejection " + checks);
    }
    interface RunnableWithException { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        check(WeatherSnapshot.coordinate("40.7128", 90) == 40.7);
        check(WeatherSnapshot.coordinate("-73,9857", 180) == -74.0);
        check(WeatherSnapshot.coordinate("0", 90) == 0);
        rejected(() -> WeatherSnapshot.coordinate("NaN", 90));
        rejected(() -> WeatherSnapshot.coordinate("Infinity", 90));
        rejected(() -> WeatherSnapshot.coordinate("90.01", 90));
        rejected(() -> WeatherSnapshot.coordinate("-180.01", 180));
        String url = WeatherSnapshot.forecastUrl(52.5234, 13.4123);
        check(url.startsWith("https://api.open-meteo.com/v1/forecast?latitude=52.5&longitude=13.4&"));
        check(!url.contains("52.5234") && !url.contains("apikey") && !url.contains("label"));
        int[] codes = {0,1,2,3,45,48,51,53,55,56,57,61,63,65,66,67,71,73,75,77,80,81,82,85,86,95,96,99};
        for (int code : codes) check(!WeatherSnapshot.description(code).isEmpty());
        rejected(() -> WeatherSnapshot.qweatherCode(42));
        long now = System.currentTimeMillis(), seconds = now / 1000, day = seconds - seconds % 86400;
        JSONObject sample = new JSONObject().put("version", 1).put("fetched", now).put("time", seconds)
            .put("label", "Test location").put("temperature", -5.5).put("humidity", 83).put("code", 73)
            .put("days", new JSONArray().put(new JSONObject().put("time", day).put("code", 73)
                .put("high", 0).put("low", -8).put("sunrise", 0).put("sunset", 0).put("uv", 0)))
            .put("hours", new JSONArray());
        check(WeatherSnapshot.parse(sample.toString(), now).getDouble("temperature") == -5.5);
        rejected(() -> WeatherSnapshot.parse(sample.toString(), now + WeatherSnapshot.MAX_AGE_MS + 1));
        rejected(() -> WeatherSnapshot.parse(sample.toString(), now - 61000));
        JSONObject invalid = new JSONObject(sample.toString()).put("humidity", 101);
        rejected(() -> WeatherSnapshot.parse(invalid.toString(), now));
        rejected(() -> WeatherSnapshot.parse(new JSONObject(sample.toString()).put("days", new JSONArray()).toString(), now));
        rejected(() -> WeatherSnapshot.parse("x".repeat(WeatherSnapshot.MAX_BYTES + 1), now));
        // An actual provider contract check uses the public Berlin example, never the user's location.
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == 200);
        JSONObject snapshot = WeatherSnapshot.fromForecast(new JSONObject(response.body()), "Berlin API test", System.currentTimeMillis());
        check(snapshot.getJSONArray("days").length() == 7);
        check(snapshot.getJSONArray("hours").length() > 0 && snapshot.getJSONArray("hours").length() <= 24);
        check(!snapshot.has("latitude") && !snapshot.has("longitude"));
        System.out.println("Passed " + checks + " weather checks including the live forecast contract.");
    }
}
