
package com.esotericsoftware.tcpserver;

import static com.esotericsoftware.minlog.Log.*;
import static com.esotericsoftware.tcpserver.Util.*;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;

import com.esotericsoftware.tcpserver.Protocol.ProtocolWrite;

/** A protocol for sending bytes. */
public class BinaryProtocolWrite implements ProtocolWrite {
	private final Object outputLock = new Object();
	private final ArrayBlockingQueue<byte[]> sends = new ArrayBlockingQueue(1024, true);

	public void writeThread (Connection connection) {
		while (!connection.isClosed()) {
			try {
				byte[] bytes = sends.take();
				sendBlocking(connection, null, bytes, 0, bytes.length);
			} catch (InterruptedException ignored) {
			}
		}
	}

	public void send (Connection connection, String message) {
		throw new UnsupportedOperationException();
	}

	/** @throws UnsupportedOperationException */
	public void send (Connection connection, String message, byte[] bytes, int offset, int count) {
		if (message != null) throw new IllegalArgumentException("message must be null.");

		if (TRACE) trace(connection.category, "Queued: " + text(bytes, offset, count));
		byte[] copy = new byte[count];
		System.arraycopy(bytes, offset, copy, 0, count);
		sends.add(copy);
	}

	public boolean sendBlocking (Connection connection, String message, byte[] bytes, int offset, int count) {
		if (message != null) throw new IllegalArgumentException("message must be null.");
		if (connection.isClosed()) return false;
		try {
			synchronized (outputLock) {
				if (TRACE) trace(connection.category, "Sent: " + text(bytes, offset, count));
				connection.output.write(bytes, offset, count);
				connection.output.flush();
			}
			return true;
		} catch (IOException ex) {
			if (ERROR && !connection.isClosed())
				error(connection.category, "Error writing to connection: " + text(bytes, offset, count), ex);
			connection.close();
			return false;
		}
	}

	static private String text (byte[] bytes, int offset, int count) {
		StringBuilder buffer = new StringBuilder(32);
		buffer.append(count);
		buffer.append(" B [");
		for (int i = offset, n = i + Math.min(16, count) - 1; i <= n; i++) {
			buffer.append(Integer.toHexString(bytes[i]));
			if (i < n) buffer.append(' ');
		}
		if (count > 16) buffer.append("...");
		buffer.append(']');
		return buffer.toString();
	}

	static public void main (String[] args) throws Exception {
		class BinaryProtocolTest extends BinaryProtocolWrite implements ProtocolRead {
			public void readThread (Connection connection) throws IOException {
				byte[] bytes = new byte[4];
				while (!connection.isClosed()) {
					int count = connection.input.read();
					if (!readFully(connection, bytes, 0, count)) break;
					System.out.println(connection.name + " received: " + text(bytes, 0, count));
					connection.receive(null, null, bytes, count);
				}
			}
		}

		TcpServer server = new TcpServer("server", "Server", 4567) {
			protected Protocol newProtocol () {
				return new BinaryProtocolTest();
			}

			public void connected (Connection connection) {
				super.connected(connection);
				connection.send(null, new byte[] {1, 1});
				connection.send(null, new byte[] {2, 2, 3});
			}

			public void receive (Connection connection, String event, String payload, byte[] bytes, int count) {
				connection.send(null, new byte[] {1, 6});
			}
		};
		server.start();

		new TcpClient("client", "Client", "localhost", 4567, new BinaryProtocolTest()) {
			public void receive (String event, String payload, byte[] bytes, int count) {
				if (bytes[0] == 1) connection.send(null, new byte[] {2, 4, 5});
			}
		}.start();
	}
}
