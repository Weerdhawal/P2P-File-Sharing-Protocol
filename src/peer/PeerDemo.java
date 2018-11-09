package peer;

import java.util.concurrent.TimeUnit;

public class PeerDemo {
	public static void main(String[] args) {
		int peerNumber = Integer.parseInt(args[0]);
		if(peerNumber == 1) {
			Peer peer1 = new Peer();
			peer1.createTracker("input.txt", "Small_file");
			try {
				TimeUnit.SECONDS.sleep(900);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			System.out.println("Peer 1 terminated");
		} else if(peerNumber == 2) {
			Peer peer2 = new Peer();
			peer2.createTracker("input.jpg", "Large_file");
			try {
				TimeUnit.SECONDS.sleep(900);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			System.out.println("Peer 2 terminated");
		} else if(peerNumber >= 3 && peerNumber <= 8) {
			Peer peerA = new Peer();
			try {
				TimeUnit.SECONDS.sleep(30); // peer 3-8 should wait 30 seconds
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			peerA.getList();
			peerA.getTracker("input.txt", true);
			peerA.downloadFile();
			peerA.getTracker("input.jpg", true);
			peerA.downloadFile();
			try {
				TimeUnit.SECONDS.sleep(120); // wait for peers to download
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		else if(peerNumber >= 9) {
			Peer peerB = new Peer();
			try {
				TimeUnit.SECONDS.sleep(90); // peer 9-13 should wait 90 seconds
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			peerB.getList();
			peerB.getTracker("input.txt", true);
			peerB.downloadFile();
			peerB.getTracker("input.jpg", true);
			peerB.downloadFile();
			try {
				TimeUnit.SECONDS.sleep(120); // wait for peers to download
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
}
