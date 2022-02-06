
package com.esotericsoftware.tcpserver;

import static com.esotericsoftware.tcpserver.Util.*;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;

public class UdpBroadcast implements Closeable {
	private final Subnets subnets;
	private final ArrayList<InetAddress> addresses = new ArrayList();
	private DatagramSocket socket;

	public UdpBroadcast (Subnets subnets) {
		this.subnets = subnets;
	}

	public UdpBroadcast (InetAddress address) {
		subnets = null;
		addresses.add(address);
	}

	public void updateAddresses () throws IOException {
		if (subnets == null) return;

		addresses.clear();
		for (Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces(); ifaces.hasMoreElements();) {
			NetworkInterface iface = ifaces.nextElement();
			if (iface.isLoopback() || !iface.isUp()) continue;

			for (Enumeration<InetAddress> iaddresses = iface.getInetAddresses(); iaddresses.hasMoreElements();) {
				byte[] ip = iaddresses.nextElement().getAddress();
				if (subnets == Subnets.classC || subnets == Subnets.classBC) {
					ip[3] = -1; // 255.255.255.0
					addresses.add(InetAddress.getByAddress(ip));
				}
				if (subnets == Subnets.classB || subnets == Subnets.classBC) {
					ip[2] = -1; // 255.255.0.0
					ip[3] = -1;
					addresses.add(InetAddress.getByAddress(ip));
				}
			}
		}
	}

	public void bind () throws IOException {
		if (socket != null) throw new IllegalStateException();

		if (addresses.isEmpty()) updateAddresses();

		socket = new DatagramSocket(null);
		socket.setReuseAddress(true);
		socket.setBroadcast(true);
		socket.bind(null);
	}

	public void broadcast (int port, byte[] buffer) throws IOException {
		if (socket == null) throw new IllegalStateException();

		DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
		packet.setPort(port);

		boolean success = false;
		IOException lastEx = null;
		for (InetAddress address : addresses) {
			try {
				packet.setAddress(address);
				socket.send(packet);
				success = true;
			} catch (IOException ex) {
				lastEx = ex;
			}
		}

		if (!success) {
			if (lastEx != null) throw lastEx;
			throw new IOException("No network interfaces to broadcast.");
		}
	}

	public DatagramSocket getSocket () {
		return socket;
	}

	public void close () {
		closeQuietly(socket);
		socket = null;
	}

	static public enum Subnets {
		/** Subnet mask: 255.255.255.0 */
		classC,

		/** Subnet mask: 255.255.0.0 */
		classB,

		/** Subnet masks: 255.255.0.0 and 255.255.255.0 */
		classBC
	}
}
