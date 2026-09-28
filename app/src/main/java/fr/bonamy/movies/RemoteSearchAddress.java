package fr.bonamy.movies;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class RemoteSearchAddress {

	private RemoteSearchAddress() {
	}

	static String find() {
		try {
			List<Candidate> candidates = new ArrayList<>();
			for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!network.isUp() || network.isLoopback()) continue;
				for (InetAddress address : Collections.list(network.getInetAddresses())) {
					if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
						candidates.add(new Candidate(network.getName(), address.getHostAddress()));
					}
				}
			}
			for (Candidate candidate : candidates) {
				if (candidate.interfaceName.startsWith("wlan")) return candidate.address;
			}
			for (Candidate candidate : candidates) {
				if (candidate.interfaceName.startsWith("eth")) return candidate.address;
			}
			return candidates.isEmpty() ? null : candidates.get(0).address;
		} catch (SocketException error) {
			return null;
		}
	}

	private static final class Candidate {
		private final String interfaceName;
		private final String address;

		private Candidate(String interfaceName, String address) {
			this.interfaceName = interfaceName;
			this.address = address;
		}
	}
}
