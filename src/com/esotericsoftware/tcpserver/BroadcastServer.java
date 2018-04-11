
package com.esotericsoftware.tcpserver;

import static com.esotericsoftware.minlog.Log.*;
import static com.esotericsoftware.tcpserver.Util.*;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Enumeration;

public class BroadcastServer extends Retry {
	static final byte[] secret = new byte[] {62, 126, -17, 61, 127, -16, 63, 125, -18};

	private int port;
	private DatagramSocket socket;
	private final byte[] buffer = new byte[secret.length];

	public BroadcastServer (String category, String name) {
		this(category, name, 0);
	}

	public BroadcastServer (String category, String name, int port) {
		super(category, name);
		this.port = port;
	}

	protected void retry () {
		try {
			socket = new DatagramSocket(port);
		} catch (Exception ex) {
			if (ERROR) error(category, "Unable to start broadcast server.", ex);
			failed();
			return;
		}
		success();
		try {
			if (INFO) info(category, "Listening on port: UDP " + port);
			while (running) {
				DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
				socket.receive(packet);
				if (!Arrays.equals(secret, packet.getData())) {
					if (DEBUG) debug(category, "Server received invalid packet.");
					continue;
				}

				packet = new DatagramPacket(secret, secret.length, packet.getAddress(), packet.getPort());
				socket.send(packet);
			}
		} catch (Exception ex) {
			if (ERROR) error(category, "Unexpected broadcast server error.", ex);
			closeQuietly(socket);
			failed();
		}
	}

	protected void stopped () {
		closeQuietly(socket);
	}

	public int getPort () {
		return port;
	}

	public void setPort (int port) {
		this.port = port;
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

	static public void main (String[] args) throws Exception {
		TRACE();

		BroadcastServer server = new BroadcastServer("broadcast", "test", 53333);
		server.start();

		System.out.println(BroadcastServer.find(53333, 1000));
	}
}
