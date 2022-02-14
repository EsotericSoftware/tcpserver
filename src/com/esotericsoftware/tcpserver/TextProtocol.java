
package com.esotericsoftware.tcpserver;

import static com.esotericsoftware.minlog.Log.*;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;

import com.esotericsoftware.tcpserver.Protocol.ProtocolRead;
import com.esotericsoftware.tcpserver.Protocol.ProtocolWrite;

/** A protocol for sending and receiving strings. */
public class TextProtocol implements ProtocolRead, ProtocolWrite {
	final Charset charset;
	final byte[] delimiter;
	private final Object outputLock = new Object();
	private final ArrayBlockingQueue<String> sends = new ArrayBlockingQueue(1024, true);

	public TextProtocol (Charset charset, String delimiter) {
		this.charset = charset;
		this.delimiter = delimiter.getBytes(charset);
	}

	public void readThread (Connection connection) throws IOException {
		TextReader reader = newTextReader(connection);
		while (!connection.isClosed()) {
			String message = reader.readLine();
			if (message == null) {
				if (reader.isClosed()) break;
				continue;
			}
			if (TRACE) trace(connection.category, "Received: " + escape(message));
			try {
				connection.receive(message, null, null, 0);
			} catch (Throwable ex) {
				if (ERROR) error(connection.category, "Error processing message: " + message, ex);
				break;
			}
		}
	}

	protected TextReader newTextReader (Connection connection) throws IOException {
		return new TextReader(connection);
	}

	public void writeThread (Connection connection) {
		while (!connection.isClosed()) {
			try {
				sendBlocking(connection, sends.take(), null, 0, 0);
			} catch (InterruptedException ignored) {
			}
		}
	}

	public void send (Connection connection, String message) {
		if (message == null) throw new IllegalArgumentException("message cannot be null.");

		if (TRACE) trace(connection.category, "Queued: " + escape(message));
		sends.add(message);
	}

	/** @throws UnsupportedOperationException */
	public void send (Connection connection, String message, byte[] bytes, int offset, int count) {
		throw new UnsupportedOperationException();
	}

	public boolean sendBlocking (Connection connection, String message, byte[] bytes, int offset, int count) {
		if (bytes != null) throw new IllegalArgumentException("bytes must be null.");
		if (connection.isClosed()) return false;
		try {
			synchronized (outputLock) {
				if (TRACE) trace(connection.category, "Sent: " + escape(message));
				bytes = message.getBytes(charset);
				connection.output.write(bytes, 0, bytes.length);
				connection.output.flush();
			}
			return true;
		} catch (IOException ex) {
			if (ERROR && !connection.isClosed()) error(connection.category, "Error writing to connection: " + count + " bytes", ex);
			connection.close();
			return false;
		}
	}

	/** Returns the message text to use for trace logging. */
	protected String escape (String message) {
		return message.trim();
	}

	public class TextReader {
		private final Connection connection;
		private byte[] bytes = new byte[1024];
		private int start, end, mark;

		TextReader (Connection connection) {
			this.connection = connection;
		}

		private boolean fill () throws IOException {
			int remaining = remaining();
			if (remaining <= 0) {
				compact();
				remaining = remaining();
				if (remaining <= 0) {
					grow();
					remaining = remaining();
				}
			}

			int count = connection.input.read(bytes, end, remaining);
			if (count == -1) {
				end = -1;
				return false;
			}
			end += count;
			return true;
		}

		public String readLine () throws IOException {
			String text = nextLine();
			if (text != null) return text;
			if (!fill()) return null;
			return nextLine();
		}

		/** @return May be null. */
		private String nextLine () {
			int length = delimiter.length;
			outer:
			for (int n = end - length + 1; mark < n; mark++) {
				for (int ii = 0; ii < length; ii++)
					if (bytes[mark + ii] != delimiter[ii]) continue outer;
				String text = new String(bytes, start, mark - start, charset);
				start = mark + length;
				mark = start;
				return text;
			}
			return null;
		}

		public void readUntil (String until) throws IOException {
			if (TRACE) trace(connection.category, "Read until: " + until);
			while (true) {
				if (until(until)) return;
				if (!fill()) throw new EOFException("EOF reading until: " + until);
			}
		}

		private boolean until (String until) {
			int length = until.length();
			outer:
			for (int n = end - length + 1; mark < n; mark++) {
				for (int i = 0; i < length; i++)
					if (bytes[mark + i] != until.charAt(i)) continue outer;
				start = mark + length;
				mark = start;
				return true;
			}
			return false;
		}

		private void compact () {
			int shift = start;
			if (shift == 0) return;
			System.arraycopy(bytes, start, bytes, 0, end - start);
			start = 0;
			end -= shift;
			mark -= shift;
		}

		private void grow () {
			byte[] newBytes = new byte[bytes.length + bytes.length / 2];
			System.arraycopy(bytes, 0, newBytes, 0, end);
			bytes = newBytes;
		}

		private int remaining () {
			return bytes.length - end;
		}

		public boolean isClosed () {
			return end == -1;
		}
	}

	static public void main (String[] args) throws Exception {
		TcpServer server = new TcpServer("server", "Server", 4567) {
			protected Protocol newProtocol () {
				return new TextProtocol(StandardCharsets.ISO_8859_1, "\r\n");
			}

			public void connected (Connection connection) {
				super.connected(connection);
				connection.send("skip this hi\r\n");
				connection.send("moo\r\n");
				connection.send("ok?\r\n");
			}

			public void receive (Connection connection, String event, String payload, byte[] bytes, int count) {
				System.out.println("Server received: " + event);
				connection.send("good\r\n");
			}
		};
		server.start();

		new TcpClient("client", "Client", "localhost", 4567, new TextProtocol(StandardCharsets.ISO_8859_1, "\r\n") {
			protected TextReader newTextReader (Connection connection) throws IOException {
				TextReader reader = super.newTextReader(connection);
				reader.readUntil("hi\r\n");
				return reader;
			}
		}) {
			public void receive (String event, String payload, byte[] bytes, int count) {
				System.out.println("Client received: " + event);
				if (event.equals("ok?")) connection.send("ok!\r\n");
			}
		}.start();
	}
}
