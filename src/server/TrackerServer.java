package server;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.concurrent.Semaphore;

import peer.Peer;
import peer.Peer.PeerWrapper;

public class TrackerServer {
	private int port; //Port the server operates on
	private String sharedFolder; //Path to the shared folder
	
	private ArrayList<Tracker> trackers; //List of active trackers
	private HashMap<Tracker, Semaphore> trackerMutexes; //Represents which tracker files are being modified to prevent access violations
	
	public TrackerServer() {
		port = 20000;
		sharedFolder = "";
		
		trackers = new ArrayList<Tracker>();
		trackerMutexes = new HashMap<Tracker, Semaphore>();
		
		File serverConfig = new File("serverThreadConfig.cfg");
		if(serverConfig.exists()) {
			try {
				Scanner serverScanner = new Scanner(serverConfig);
				port = Integer.parseInt(serverScanner.nextLine());
				sharedFolder = serverScanner.nextLine();
				serverScanner.close();
			} catch (NoSuchElementException | FileNotFoundException e) {
				e.printStackTrace();
			}
		}
	}
	
	//Main loop; listens for connections.
	public void listen() {
		try(ServerSocket listenSocket = new ServerSocket(port);){ //ServerSockets are sockets that can accept multiple connections on the same port.
			while(true) {
				Socket clientSocket = listenSocket.accept();
				ClientHandler clientHandler = new ClientHandler(clientSocket);
				Thread clientThread = new Thread(clientHandler);
				clientThread.start();
			}
		} catch(IOException e) {
			e.printStackTrace();
			return;
		}
	}
	
	//Responds to a client communicating, implements all functioning.
	public void communicate(Socket clientSocket) {
		Scanner lineReader = null;
		try(BufferedReader textInput = new BufferedReader(new InputStreamReader(clientSocket.getInputStream())); //This BufferedReader will buffer inputs from the socket and return lines as strings.
			PrintWriter textOutput = new PrintWriter(clientSocket.getOutputStream());) { //PrintWriters let you push more accessible data types like strings to the socket
			
			String inputLine = textInput.readLine();
			System.out.println("RECV: " + inputLine);
			lineReader = new Scanner(inputLine);
			lineReader.useDelimiter("[ <>]");
			String command = lineReader.next();
			//System.out.println("Command is " + command + ".");
			
			if(command.equals("createtracker")) {
				try {
					Tracker tracker = new Tracker();
					tracker.fileName = lineReader.next();
					if(getTracker(tracker.fileName) != null) {
						textOutput.println("<createtracker ferr>");
						System.out.println("SEND: <createtracker ferr>");
						textOutput.flush();
						lineReader.close();
						return;
					}
					tracker.fileSize = lineReader.nextInt();
					tracker.description = lineReader.next();
					tracker.md5 = lineReader.next();
					String ipAddress = lineReader.next();
					int portNumber = lineReader.nextInt();
					Peer.PeerWrapper creator = new Peer.PeerWrapper(ipAddress, portNumber, 0, tracker.fileSize, System.currentTimeMillis());
					tracker.peers.add(creator);
					trackers.add(tracker);
					trackerMutexes.put(tracker, new Semaphore(1));
					tracker.saveFile(sharedFolder);					
					textOutput.println("<createtracker succ>");
					System.out.println("SEND: <createtracker succ>");
					textOutput.flush();
					lineReader.close();
					return;
				} catch(Exception e) {
					textOutput.println("<createtracker fail>");
					System.out.println("SEND: <createtracker fail>");
					textOutput.flush();
					lineReader.close();
					e.printStackTrace();
					return;
				}
			} else if(command.equals("updatetracker")) {
				try {
					String fileName = lineReader.next();
					Tracker tracker = getTracker(fileName);
					if(tracker == null) {
						textOutput.println("<updatetracker " + fileName + " ferr>");
						System.out.println("SEND: <updatetracker " + fileName + " ferr>");
						textOutput.flush();
						lineReader.close();
						return;
					}
					int startByte = lineReader.nextInt();
					int endByte = lineReader.nextInt();
					String ipAddress = lineReader.next();
					int portNumber = lineReader.nextInt();
					
					Peer.PeerWrapper peerMatch = null;
					for(Peer.PeerWrapper peer : tracker.peers) {
						if(peer.hostName.equals(ipAddress) && peer.port == portNumber) {
							peerMatch = peer;
							peer.startByte = startByte;
							peer.endByte = endByte;
							peer.timeStamp = System.currentTimeMillis();
						}
					}
					if(peerMatch == null) {
						Peer.PeerWrapper newPeer = new Peer.PeerWrapper(ipAddress, portNumber, startByte, endByte, System.currentTimeMillis());
						tracker.peers.add(newPeer);
						tracker.peers.sort(null);
					}
					trackerMutexes.get(tracker).acquire();
					tracker.saveFile(sharedFolder);
					trackerMutexes.get(tracker).release();
					
					textOutput.println("<updatetracker " + fileName + " succ>");
					System.out.println("SEND: <updatetracker " + fileName + " succ>");
					textOutput.flush();
					lineReader.close();
					return;
				} catch(Exception e) {
					String fileName = lineReader.next();
					textOutput.println("<updatetracker " + fileName + " fail>");
					System.out.println("SEND: <updatetracker " + fileName + " fail>");
					textOutput.flush();
					lineReader.close();
					e.printStackTrace();
					return;
				}
			} else if(command.equals("REQ")) {
				textOutput.println("<REP LIST " + trackers.size() + ">");
				System.out.println("SEND: <REP LIST " + trackers.size() + ">");
				for(int i = 0; i < trackers.size(); i++) {
					Tracker tracker = trackers.get(i);
					textOutput.println("<" + (i + 1) + " " + tracker.fileName + " " + tracker.fileSize + " " + tracker.md5 + ">");
					System.out.println("SEND: <" + (i + 1) + " " + tracker.fileName + " " + tracker.fileSize + " " + tracker.md5 + ">");
				}
				textOutput.println("<REP LIST END>");
				System.out.println("SEND: <REP LIST END>");
				textOutput.flush();
			} else if(command.equals("GET")) {
				String fileName = lineReader.next(); //TODO: This should really be the filename of the tracker file.
				Tracker tracker = getTracker(fileName);
				if(tracker == null) {
					textOutput.println("<REP GET FERR>");
					System.out.println("SEND: <REP GET FERR>");
					textOutput.flush();
					lineReader.close();
					return;
				}
				textOutput.println("<REP GET BEGIN>");
				System.out.println("SEND: <REP GET BEGIN>");
				try {
					trackerMutexes.get(tracker).acquire();
					Scanner fileScanner = new Scanner(new File(tracker.trackerFileName));
					while(fileScanner.hasNext()) {
						String line = fileScanner.nextLine();
						textOutput.println(line);
						System.out.println("SEND: " + line);
					}
					fileScanner.close();
					
					String trackerMD5 = Peer.byteToHex(Peer.computeMD5(new File(tracker.trackerFileName)));
					textOutput.println("<REP GET END " + trackerMD5 + ">"); //TODO: Send MD5 of tracker file
					System.out.println("SEND: <REP GET END " + trackerMD5 + ">");
					trackerMutexes.get(tracker).release();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				textOutput.flush();
				lineReader.close();
				return;
			}
			lineReader.close();
		} catch(IOException e) {
			e.printStackTrace();
			//lineReader.close();
			return;
		}
	}
	
	//Returns the tracker for the given filename
	public Tracker getTracker(String fileName) {
		for(Tracker tracker : trackers) {
			if(fileName.equalsIgnoreCase(tracker.fileName)) {
				return tracker;
			}
		}
		return null;
	}
	
	public static void main(String[] args) {
		TrackerServer server = new TrackerServer();
		System.out.println("Server started.");
		server.listen();
	}
	
	//Stores all the information a tracker file would have
	public static class Tracker { //TODO: Trackers should be able to be stored as/read from files stored on the server
		public String fileName;
		public String trackerFileName;
		public int fileSize;
		public String description;
		public String md5;
		public ArrayList<Peer.PeerWrapper> peers;
		
		public Tracker() {
			peers = new ArrayList<Peer.PeerWrapper>();
		}
		
		//Reads a tracker in from an existing file
		public Tracker(String trackerFileName) {
			super();
			this.trackerFileName = trackerFileName;
			
			try(FileInputStream input = new FileInputStream(trackerFileName);
				Scanner inputScanner = new Scanner(input);) {
				boolean startPeers = false;
				while(inputScanner.hasNext()) {
					String reply = inputScanner.nextLine();
					System.out.println("RECV: " + reply);
					if(reply.charAt(0) == '#') {
						continue;
					}
					Scanner lineReader = new Scanner(reply);
					if(!startPeers) {
						lineReader.useDelimiter(" ");
						String value = lineReader.next();
						if(value.equals("Filename:")) {
							fileName = lineReader.next();
						} else if(value.equals("Filesize:")) {
							fileSize = lineReader.nextInt();
						} else if(value.equals("Description:")) {
							description = lineReader.next();
						} else if(value.equals("MD5:")) {
							md5 = lineReader.next();
							startPeers = true;
						}
					} else {
						lineReader.useDelimiter(":");
						String ipAddress = lineReader.next();
						System.out.println(ipAddress);
						int port = lineReader.nextInt();
						int startByte = lineReader.nextInt();
						int endByte = lineReader.nextInt();
						long timeStamp = lineReader.nextLong();
						PeerWrapper peer = new PeerWrapper(ipAddress, port, startByte, endByte, timeStamp);
						peers.add(peer);
					}
					lineReader.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		//String-formatted tracker file
		public String toString() {
			String output = "Filename: " + fileName + "\n";
			output += "Filesize: " + fileSize + "\n";
			output += "Description: " + description + "\n";
			output += "MD5: " + md5 + "\n";
			output += "# all comments must begin with # and must be ignored by the file parser\n";
			output += "# following the above fields about file to be shared will be list of peers sharing this file\n";
			for(Peer.PeerWrapper peer : peers) {
				output += peer.hostName + ":" + peer.port + ":" + peer.startByte + ":" + peer.endByte + ":" + peer.timeStamp + "\n";
			}
			return output;
		}
		
		//Saves the tracker data into a file
		public void saveFile(String sharedFolder) {
			trackerFileName = sharedFolder + "/" + fileName + ".track";
			File file = new File(trackerFileName);
			file.delete();
			try(FileWriter writer = new FileWriter(file);) {
				file.createNewFile();
				writer.write(toString());
			} catch (IOException e) {
				e.printStackTrace();
				return;
			}
		}
	}
	
	//Class which runs communicate in a thread
	private class ClientHandler implements Runnable {
		private Socket clientSocket;
	
		public ClientHandler(Socket clientSocket) {
			this.clientSocket = clientSocket;
		}
		
		//This is the method that runs when the thread starts.
		public void run() {
			communicate(clientSocket);
		}
	}
}
