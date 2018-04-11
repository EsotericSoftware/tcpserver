
package com.esotericsoftware.tcpserver;

import static com.esotericsoftware.minlog.Log.*;
import static com.esotericsoftware.tcpserver.BroadcastServer.*;
import static com.esotericsoftware.tcpserver.Util.*;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Enumeration;

public class BroadcastClient extends Retry {
	private int port, timeoutMillis = 3000;
	private final byte[] buffer = new byte[secret.length];

	public BroadcastClient (String category, String name) {
		this(category, name, 0);
	}

	public BroadcastClient (String category, String name, int port) {
		super(category, name);
		this.port = port;
		setRetryDelays(6);
	}

	protected void retry () {
		InetAddress address = find(port, timeoutMillis);
		if (address != null) found(address);
		failed(); // Always sleep.
	}

	protected void found (InetAddress address) {
	}

	public int getPort () {
		return port;
	}

	public void setPort (int port) {
		this.port = port;
	}

	public int getTimeout () {
		return timeoutMillis;
	}

	public void setTimeout (int millis) {
		timeoutMillis = millis;
	}

	static public InetAddress find (int port, int timeoutMillis) {
		DatagramSocket socket = null;
		try {
			socket = new DatagramSocket();
			for (Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces(); ifaces.hasMoreElements();) {
				NetworkInterface iface = ifaces.nextElement();
				for (Enumeration<InetAddress> addresses = iface.getInetAddresses(); addresses.hasMoreElements();) {
					byte[] ip = addresses.nextElement().getAddress();
					ip[3] = -1; // 255.255.255.0
					try {
						socket.send(new DatagramPacket(secret, secret.length, InetAddress.getByAddress(ip), port));
					} catch (Exception ignored) {
					}
					ip[2] = -1; // 255.255.0.0
					try {
						socket.send(new DatagramPacket(secret, secret.length, InetAddress.getByAddress(ip), port));
					} catch (Exception ignored) {
					}
				}
			}
			if (DEBUG) debug("broadcast", "Broadcasted on port: UDP " + port);

			socket.setSoTimeout(timeoutMillis);
			DatagramPacket packet = new DatagramPacket(secret, secret.length);
			try {
				socket.receive(packet);
			} catch (SocketTimeoutException ex) {
				if (INFO) info("broadcast", "Host discovery timed out.");
				return null;
			}
			if (!Arrays.equals(secret, packet.getData())) {
				if (DEBUG) debug("broadcast", "Received invalid packet.");
				return null;
			}
			if (INFO) info("broadcast", "Discovered server: " + packet.getAddress());
			return packet.getAddress();
		} catch (IOException ex) {
			if (ERROR) error("broadcast", "Host discovery failed.", ex);
			return null;
		} finally {
			closeQuietly(socket);
		}
	}
}
