package fr.bonamy.movies.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Tiny app-scoped HTTP server used to search and navigate Movies from a phone. */
public final class RemoteSearchServer {

	public interface Listener {
		void onSearch(String query);

	}

	private static final int FIRST_PORT = 8070;
	private static final int LAST_PORT = 8079;
	private static final int MAX_QUERY_LENGTH = 200;

	private final Listener listener;
	private final int firstPort;
	private final int lastPort;
	private final String pageTemplate;
	private final byte[] icon;
	private final byte[] favicon;
	private volatile boolean running;
	private ServerSocket serverSocket;
	private Thread serverThread;

	public RemoteSearchServer(Listener listener, String pageTemplate, byte[] icon) {
		this(listener, FIRST_PORT, LAST_PORT, pageTemplate, icon);
	}

	public RemoteSearchServer(
		Listener listener,
		int firstPort,
		int lastPort,
		String pageTemplate,
		byte[] icon
	) {
		this.listener = listener;
		this.firstPort = firstPort;
		this.lastPort = lastPort;
		this.pageTemplate = pageTemplate;
		this.icon = icon;
		this.favicon = createFavicon(icon);
	}

	public synchronized String start(String address) throws IOException {
		if (running) return url(address, serverSocket.getLocalPort());

		IOException lastError = null;
		for (int port = firstPort; port <= lastPort; port++) {
			ServerSocket candidate = new ServerSocket();
			try {
				candidate.setReuseAddress(true);
				candidate.bind(new InetSocketAddress(port));
				serverSocket = candidate;
				break;
			} catch (IOException error) {
				try {
					candidate.close();
				} catch (IOException ignored) {
				}
				lastError = error;
			}
		}
		if (serverSocket == null) {
			throw lastError == null ? new IOException("No remote search port available") : lastError;
		}

		running = true;
		ServerSocket listeningSocket = serverSocket;
		serverThread = new Thread(() -> serve(listeningSocket), "movies-remote-search");
		serverThread.start();
		return url(address, serverSocket.getLocalPort());
	}

	public synchronized void stop() {
		running = false;
		if (serverSocket != null) {
			try {
				serverSocket.close();
			} catch (IOException ignored) {
			}
		}
		serverSocket = null;
		serverThread = null;
	}

	private void serve(ServerSocket listeningSocket) {
		while (running && !listeningSocket.isClosed()) {
			try {
				handle(listeningSocket.accept());
			} catch (IOException error) {
				if (running) error.printStackTrace();
			}
		}
	}

	private void handle(Socket socket) {
		try (Socket client = socket) {
			client.setSoTimeout(4000);
			BufferedReader reader = new BufferedReader(new InputStreamReader(
				client.getInputStream(),
				StandardCharsets.US_ASCII
			));
			String requestLine = reader.readLine();
			String line;
			do {
				line = reader.readLine();
			} while (line != null && !line.isEmpty());

			if (requestLine == null || !requestLine.startsWith("GET ")) {
				respond(client, 405, "Method Not Allowed", errorPage("Use the search form."));
				return;
			}

			String target = requestLine.split(" ", 3)[1];
			URI uri = URI.create(target);
			if (("/favicon.ico".equals(uri.getPath())
				|| "/movies-favicon.ico".equals(uri.getPath()))
				&& favicon != null) {
				respond(client, 200, "OK", "image/x-icon", favicon);
				return;
			}
			if (("/icon.png".equals(uri.getPath())
				|| "/apple-touch-icon.png".equals(uri.getPath())) && icon != null) {
				respond(client, 200, "OK", "image/png", icon);
				return;
			}
			if ("/".equals(uri.getPath())) {
				respond(client, 200, "OK", page(null, null));
				return;
			}
			if (!"/search".equals(uri.getPath())) {
				respond(client, 404, "Not Found", errorPage("Page not found."));
				return;
			}

			String query = queryParameter(uri.getRawQuery(), "q");
			if (query == null || query.trim().length() < 2) {
				respond(client, 400, "Bad Request", page(null, "Enter at least two characters."));
				return;
			}
			query = query.trim();
			if (query.length() > MAX_QUERY_LENGTH) query = query.substring(0, MAX_QUERY_LENGTH);
			listener.onSearch(query);
			respond(client, 200, "OK", page(query, null));
		} catch (Exception ignored) {
			// A disconnected browser must not stop the TV listener.
		}
	}

	private static void respond(
		Socket socket,
		int status,
		String reason,
		String body
	) throws IOException {
		respond(
			socket,
			status,
			reason,
			"text/html; charset=utf-8",
			body.getBytes(StandardCharsets.UTF_8)
		);
	}

	private static void respond(
		Socket socket,
		int status,
		String reason,
		String contentType,
		byte[] content
	) throws IOException {
		String headers = String.format(
			Locale.US,
			"HTTP/1.1 %d %s\r\n" +
				"Content-Type: %s\r\n" +
				"Content-Length: %d\r\n" +
				"Cache-Control: no-store\r\n" +
				"X-Content-Type-Options: nosniff\r\n" +
				"Connection: close\r\n\r\n",
			status,
			reason,
			contentType,
			content.length
		);
		OutputStream output = socket.getOutputStream();
		output.write(headers.getBytes(StandardCharsets.US_ASCII));
		output.write(content);
		output.flush();
	}

	static String queryParameter(String rawQuery, String name) {
		if (rawQuery == null) return null;
		for (String parameter : rawQuery.split("&")) {
			String[] parts = parameter.split("=", 2);
			if (decode(parts[0]).equals(name)) {
				return parts.length == 2 ? decode(parts[1]) : "";
			}
		}
		return null;
	}

	private static String decode(String value) {
		try {
			return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
		} catch (UnsupportedEncodingException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static String url(String address, int port) {
		return "http://" + address + ":" + port;
	}

	private static byte[] createFavicon(byte[] png) {
		if (png == null) return null;
		byte[] ico = new byte[22 + png.length];
		ico[2] = 1;       // ICO image
		ico[4] = 1;       // one image
		ico[6] = (byte) 192;
		ico[7] = (byte) 192;
		ico[10] = 1;      // one color plane
		ico[12] = 32;     // 32 bits per pixel
		writeLittleEndian(ico, 14, png.length);
		writeLittleEndian(ico, 18, 22);
		System.arraycopy(png, 0, ico, 22, png.length);
		return ico;
	}

	private static void writeLittleEndian(byte[] bytes, int offset, int value) {
		bytes[offset] = (byte) value;
		bytes[offset + 1] = (byte) (value >>> 8);
		bytes[offset + 2] = (byte) (value >>> 16);
		bytes[offset + 3] = (byte) (value >>> 24);
	}

	private String page(String submittedQuery, String error) {
		String message = "";
		if (submittedQuery != null) {
			message = "<p class=\"status success\">Sent <strong>" + escape(submittedQuery) +
				"</strong> to your TV.</p>";
		} else if (error != null) {
			message = "<p class=\"status error\">" + escape(error) + "</p>";
		}
		return pageTemplate.replace("<!--STATUS-->", message);
	}

	private String errorPage(String message) {
		return page(null, message);
	}

	private static String escape(String value) {
		return value.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;")
			.replace("'", "&#39;");
	}
}
