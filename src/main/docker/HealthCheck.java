import java.net.HttpURLConnection;
import java.net.URL;

public final class HealthCheck {
	public static void main(String[] args) throws Exception {
		HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:8080/ready").openConnection();
		connection.setConnectTimeout(1500);
		connection.setReadTimeout(1500);
		connection.setRequestMethod("GET");
		if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
			System.exit(1);
		}
		connection.disconnect();
	}
}
