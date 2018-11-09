package peer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.InputMismatchException;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import server.TrackerServer;

//Class which represents the peer/client
public class Peer {
	private String trackAddress; //IP of the tracker server
	private int trackPort; //Port of the tracker server
	private int basePort; //Port of this peer, as given by the config file
	private int currPort; //Port associated with the currently active file
	
	private ArrayList<PeerWrapper> peers; //List of peers for the current file
	private static final int MAXIMUM_CONCURRENT_DOWNLOADS = 10; //Maximum number of unique peers to download from at once
	private BitSet currDownloads; //List of which peers are currently being downloaded from, to prevent collision
	
	private static int updatePeriod; //Rate at which tracker files are updated and at which updates are sent
	
	private static final int PIECE_SIZE = 1024; //Size of segments of the file to download
	private String sharedFolder; //Path to the shared folder
	private File currFile; //The current file being downloaded
	private File currTrackFile; //The tracker file associated with the current file
	private int currFileSize; //The size of the current file
	private BitSet piecesNeeded; //Lists which pieces of the current file still need to be downloaded
	private int totalPieces; //Number of pieces the file is broken into
	private byte[] md5; //MD5 hash of the current file, as reported by the tracker file
	
	private Semaphore piecesMutex; //Mutex to prevent collisions in accessing piecesNeeded
	private Semaphore downloadsMutex; //Mutex to prevent collissions in accessing currDownloads
	
	public Peer() {
		trackPort = 20000;
		trackAddress = "127.0.0.1";
		updatePeriod = 1000;
		basePort = 20001;
		sharedFolder = "";
		
		peers = new ArrayList<PeerWrapper>();
		currDownloads = new BitSet(MAXIMUM_CONCURRENT_DOWNLOADS);
		piecesMutex = new Semaphore(1);
		downloadsMutex = new Semaphore(1);
		
		File clientConfig = new File("clientThreadConfig.cfg");
		if(clientConfig.exists()) {
			try {
				Scanner clientScanner = new Scanner(clientConfig);
				trackPort = Integer.parseInt(clientScanner.nextLine());
				trackAddress = clientScanner.nextLine();
				updatePeriod = Integer.parseInt(clientScanner.nextLine()) * 1000;
				clientScanner.close();
			} catch (NoSuchElementException | FileNotFoundException e) {
				e.printStackTrace();
			}
		}
		
		File serverConfig = new File("serverThreadConfig.cfg");
		if(serverConfig.exists()) {
			try {
				Scanner serverScanner = new Scanner(serverConfig);
				basePort = Integer.parseInt(serverScanner.nextLine());
				sharedFolder = serverScanner.nextLine();
				serverScanner.close();
			} catch (NoSuchElementException | FileNotFoundException e) {
				e.printStackTrace();
			}
		}
		
		currPort = basePort;
	}
	
	//Loads a file on the computer into the program. Everything is done in-place, so there is little memory overhead.
	public void loadFile(String fileName) {
		currFile = new File(sharedFolder + "/" + fileName);
		currFileSize = (int)currFile.length();
		int totalPieces = (int)Math.ceil(currFileSize / (double)PIECE_SIZE);
		piecesNeeded = new BitSet(totalPieces);
		System.out.println("Calculating file MD5 hash. Please wait...");
		md5 = computeMD5(currFile);
		
		BroadcastHandler broadcaster = new BroadcastHandler(currFile, currPort);
		Thread broadcastThread = new Thread(broadcaster);
		broadcastThread.setDaemon(true);
		broadcastThread.start();
	}
	
	//Create a new, empty file with the specified name.
	public void createFile(String fileName, int fileSize) {
		currFile = new File(sharedFolder + "/" + fileName);
		currFileSize = fileSize;
		totalPieces = (int)Math.ceil(currFileSize / (double)PIECE_SIZE);
		piecesNeeded = new BitSet(totalPieces);
		piecesNeeded.set(0, totalPieces);
		
		try/*(FileOutputStream fileWriter = new FileOutputStream(currFile);)*/ {
			currFile.createNewFile();
		//	for(int i = 0; i < currFileSize; i++) {
		//		fileWriter.write(0);
		//	}
		} catch(IOException e) {
			e.printStackTrace();
			return;
		}
		
		BroadcastHandler broadcaster = new BroadcastHandler(currFile, currPort);
		Thread broadcastThread = new Thread(broadcaster);
		broadcastThread.setDaemon(true);
		broadcastThread.start();
	}
	
	//Check for existing tracker files and resume download if possible
	public void resumeFile() {
		File sharedFolderFile = new File(sharedFolder);
		File[] contents = sharedFolderFile.listFiles();
		String resumeTracker = null;
		for(int i = 0; i < contents.length; i++) {
			String fileName = contents[i].getName();
			if(fileName.endsWith(".track")) {
				resumeTracker = fileName;
				break;
			}
		}
		
		if(resumeTracker != null) {
			System.out.println("INCOMPLETE DOWNLOAD FOUND: RESUMING");
			
			TrackerServer.Tracker tracker = new TrackerServer.Tracker(resumeTracker);
			currFile = new File(tracker.fileName);
			currTrackFile = new File(tracker.trackerFileName);
			currFileSize = tracker.fileSize;
			md5 = hexToByte(tracker.md5);
			peers = tracker.peers;
			
			long endByte = currFile.length();
			int totalPieces = (int)Math.ceil(currFileSize / (double)PIECE_SIZE);
			piecesNeeded = new BitSet(totalPieces);
			for(int i = getPieceNumber(endByte); i < totalPieces; i++) {
				piecesNeeded.set(i);
			}
			
			BroadcastHandler broadcaster = new BroadcastHandler(currFile, currPort);
			Thread broadcastThread = new Thread(broadcaster);
			broadcastThread.setDaemon(true);
			broadcastThread.start();
		}
	}
	
	//Get the start byte of the first contiguous completed segment.
	public long getStartByte() {
		return piecesNeeded.nextClearBit(0) * PIECE_SIZE;
	}
	
	//Get the end byte of the first contiguous completed segment.
	public long getEndByte() {
		try {
			piecesMutex.acquire();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		//System.out.println(piecesNeeded.toString());
		int startBit = piecesNeeded.nextClearBit(0);
		if(startBit >= totalPieces) {
			piecesMutex.release();
			return 0;
		}
		//System.out.println(piecesNeeded.toString());
		long endByte = piecesNeeded.nextSetBit(startBit) * PIECE_SIZE;
		piecesMutex.release();
		if(endByte < 0) {
			return currFileSize;
		} else {
			return endByte;
		}
	}
	
	//Get the starting byte for the given piece.
	public int getPieceStartByte(int piece) {
		return piece * PIECE_SIZE;
	}
	
	//Get the ending byte for the given piece.
	public int getPieceEndByte(int piece) {
		int endByte = (piece + 1) * PIECE_SIZE;
		if(endByte > currFileSize) {
			endByte = (int)currFileSize;
		}
		return endByte;
	}
	
	//Get a piece number given a byte number.
	public int getPieceNumber(long byteNumber) {
		return (int)(byteNumber / PIECE_SIZE);
	}
	
	//Read bytes directly from the original file
	public static byte[] getBytesFromFile(File file, long startByte, long endByte) {
		Set<OpenOption> options = new HashSet<OpenOption>();
	    options.add(StandardOpenOption.READ);
		try (SeekableByteChannel channel = Files.newByteChannel(file.toPath(), options)) {
			ByteBuffer buffer = ByteBuffer.allocate((int)(endByte - startByte));
			channel.position(startByte);
			channel.read(buffer);
			channel.close();
			return buffer.array();
		} catch(IOException e) {
			System.err.println(e.getMessage());
			return null;
		}
	}
	
	//Write bytes directly to the original file
	public static void writeBytesToFile(File file, long startByte, byte[] writeBytes) {
		Set<OpenOption> options = new HashSet<OpenOption>();
	    options.add(StandardOpenOption.WRITE);
	    options.add(StandardOpenOption.CREATE);
		try (SeekableByteChannel channel = Files.newByteChannel(file.toPath(), options)) {
			ByteBuffer buffer = ByteBuffer.wrap(writeBytes);
			channel.position(startByte);
			channel.write(buffer);
			channel.close();
		} catch(IOException e) {
			System.err.println(e.getMessage());
		}
	}
	
	//Protocol for the createtracker function which creates a tracker for a file on the tracker server
	public boolean createTracker(String fileName, String description) {
		loadFile(fileName);
		
		try(Socket trackerSocket = new Socket(trackAddress, trackPort);
			PrintWriter textOutput = new PrintWriter(trackerSocket.getOutputStream());
			BufferedReader textInput = new BufferedReader(new InputStreamReader(trackerSocket.getInputStream()));) {
			
			textOutput.println("<createtracker " + fileName + " " + currFileSize + " " + description + " " + byteToHex(md5) + " " + InetAddress.getLocalHost().getHostAddress() + " " + currPort + ">");
			System.out.println("SEND: <createtracker " + fileName + " " + currFileSize + " " + description + " " + byteToHex(md5) + " " + InetAddress.getLocalHost().getHostAddress() + " " + currPort + ">");
			textOutput.flush();
			
			String reply = textInput.readLine();
			System.out.println("RECV: " + reply);
			
			if(reply.endsWith("succ>")) {
				return true;
			} else {
				return false;
			}
			
		} catch(IOException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	//Override for createtracker for required testing
	public boolean createTracker(String fileName, int fileSize, String description, String md5, String ipAddress, int portNumber) {
		loadFile(fileName);
		
		try(Socket trackerSocket = new Socket(trackAddress, trackPort);
			PrintWriter textOutput = new PrintWriter(trackerSocket.getOutputStream());
			BufferedReader textInput = new BufferedReader(new InputStreamReader(trackerSocket.getInputStream()));) {
			
			textOutput.println("<createtracker " + fileName + " " + fileSize + " " + description + " " + md5 + " " + ipAddress + " " + portNumber + ">");
			System.out.println("SEND: <createtracker " + fileName + " " + fileSize + " " + description + " " + md5 + " " + ipAddress + " " + portNumber + ">");
			textOutput.flush();
			
			String reply = textInput.readLine();
			System.out.println("RECV: " + reply);
			
			if(reply.endsWith("succ>")) {
				return true;
			} else {
				return false;
			}
			
		} catch(IOException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	//Protocol for the updatetracker function which updates the tracker for the current file on the tracker server
	public boolean updateTracker() {
		try(Socket trackerSocket = new Socket(trackAddress, trackPort);
			PrintWriter textOutput = new PrintWriter(trackerSocket.getOutputStream());
			BufferedReader textInput = new BufferedReader(new InputStreamReader(trackerSocket.getInputStream()));) {
			
			textOutput.println("<updatetracker " + currFile.getName() + " " + getStartByte() + " " + getEndByte() + " " + InetAddress.getLocalHost().getHostAddress() + " " + currPort + ">");
			System.out.println("SEND: <updatetracker " + currFile.getName() + " " + getStartByte() + " " + getEndByte() + " " + InetAddress.getLocalHost().getHostAddress() + " " + currPort + ">");
			textOutput.flush();
			
			String reply = textInput.readLine();
			System.out.println("RECV: " + reply);
			
			if(reply.endsWith("succ>")) {
				return true;
			} else {
				return false;
			}
				
		} catch(IOException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	//Override for updatetracker for required testing
	public boolean updateTracker(String fileName, int startByte, int endByte, String ipAddress, int portNumber) {
		try(Socket trackerSocket = new Socket(trackAddress, trackPort);
			PrintWriter textOutput = new PrintWriter(trackerSocket.getOutputStream());
			BufferedReader textInput = new BufferedReader(new InputStreamReader(trackerSocket.getInputStream()));) {
			
			textOutput.println("<updatetracker " + fileName + " " + startByte + " " + endByte + " " + ipAddress + " " + portNumber + ">");
			System.out.println("SEND: <updatetracker " + fileName + " " + startByte + " " + endByte + " " + ipAddress + " " + portNumber + ">");
			textOutput.flush();
			
			String reply = textInput.readLine();
			System.out.println("RECV: " + reply);
			
			if(reply.endsWith("succ>")) {
				return true;
			} else {
				return false;
			}
					
		} catch(IOException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	//Protocol for the GET function which downloads a tracker file from the tracker server
	public boolean getTracker(String fileName, boolean restart) {
		try(Socket trackerSocket = new Socket(trackAddress, trackPort);
			PrintWriter textOutput = new PrintWriter(trackerSocket.getOutputStream());
			BufferedReader textInput = new BufferedReader(new InputStreamReader(trackerSocket.getInputStream()));) {
			
			textOutput.println("<GET " + fileName + ">");
			System.out.println("SEND: <GET " + fileName + ">");
			textOutput.flush();
			
			String reply = textInput.readLine();
			System.out.println("RECV: " + reply);
			
			if(!reply.equals("<REP GET BEGIN>")) {
				return false;
			}
			
			boolean startPeers = false;
			
			TrackerServer.Tracker tracker = new TrackerServer.Tracker();
			String trackerMD5 = "";
			
			while(true) {
				reply = textInput.readLine();
				System.out.println("RECV: " + reply);
				if(reply.charAt(0) == '#') {
					continue;
				}
				Scanner lineReader = new Scanner(reply);
				if(!startPeers || reply.contains("REP GET END")) {
					lineReader.useDelimiter("[ <>]");
					String value = lineReader.next();
					if(value.equals("Filename:")) {
						tracker.fileName = lineReader.next();
					} else if(value.equals("Filesize:")) {
						tracker.fileSize = lineReader.nextInt();
					} else if(value.equals("Description:")) {
						tracker.description = lineReader.next();
					} else if(value.equals("MD5:")) {
						tracker.md5 = lineReader.next();
						startPeers = true;
					} else if(value.equals("REP")) {
						lineReader.next();
						lineReader.next();
						trackerMD5 = lineReader.next();
						break;
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
					tracker.peers.add(peer);
				}
				lineReader.close();
			}
			
			tracker.saveFile(sharedFolder);
			currTrackFile = new File(tracker.trackerFileName);
			
			String actualTrackerMD5 = byteToHex(computeMD5(new File(tracker.trackerFileName)));
			if(!trackerMD5.equals(actualTrackerMD5)) {
				System.out.println("Error! Tracker file verification failed.");
				System.out.println("TRACK: " + trackerMD5);
				System.out.println("LOCAL: " + actualTrackerMD5);
				return false;
			}
			
			if(restart) {
				createFile(tracker.fileName, tracker.fileSize);
			}
			
			md5 = hexToByte(tracker.md5);
			peers = tracker.peers;
			return true;
			
		} catch(IOException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	//Protocol for the LIST function which returns a list of tracker files on the tracker server
	public void getList() {
		try(Socket trackerSocket = new Socket(trackAddress, trackPort);
			PrintWriter textOutput = new PrintWriter(trackerSocket.getOutputStream());
			BufferedReader textInput = new BufferedReader(new InputStreamReader(trackerSocket.getInputStream()));) {
			
			textOutput.println("<REQ LIST>");
			System.out.println("SEND: <REQ LIST>");
			textOutput.flush();
			
			String reply = textInput.readLine();
			System.out.println("RECV: " + reply);
			
			if(!reply.startsWith("<REP LIST")) {
				return;
			}
			
			while(true) {
				reply = textInput.readLine();
				System.out.println("RECV: " + reply);
				if(reply.equals("<REP LIST END>")) {
					break;
				}
				System.out.println(reply);
			}
			
		} catch(IOException e) {
			e.printStackTrace();
			return;
		}
	}
	
	//Computes the md5 hash of a file
	public static byte[] computeMD5(File file) {
		try(FileInputStream fileReader = new FileInputStream(file);) {
			MessageDigest digester = MessageDigest.getInstance("MD5");
			while(fileReader.available() > 0) {
				digester.update((byte)fileReader.read());
			}
			return digester.digest();
		} catch(IOException | NoSuchAlgorithmException e) {
			e.printStackTrace();
			return null;
		}
	}
	
	//Converts an array of bytes to a hexadecimal string
	public static String byteToHex(byte[] input) {
		String output = "";
		for(int i = 0; i < input.length; i++) {
			String segment = Integer.toString((int)input[i] + 127, 16);
			if(segment.length() == 1) {
				segment = "0" + segment;
			}
			output += segment;
		}
		return output;
	}
	
	//Converts a hexadecimal string to an array of bytes
	public static byte[] hexToByte(String input) {
		byte[] output = new byte[input.length() / 2];
		for(int i = 0; i < input.length(); i += 2) {
			output[i / 2] = (byte)(Integer.parseInt(input.substring(i, i + 2), 16) - 127);
		}
		return output;
	}
	
	//Main loop of the peer server. Waits for connections and sends the requested pieces to them.
	public void broadcastFile(File file, int port) {
		try(ServerSocket broadcastSocket = new ServerSocket(port);){ //ServerSockets are sockets that can accept multiple connections on the same port.
			while(true) {
				Socket targetSocket = broadcastSocket.accept();
				TransmitHandler transmitter = new TransmitHandler(targetSocket, file);
				Thread transmitThread = new Thread(transmitter);
				transmitThread.start(); //Start a new thread which is running the transmitThread method.
			}
		} catch(IOException e) {
			e.printStackTrace();
			return;
		}
	}
	
	//Serverside protocol to send a piece of a file to a target machine. Interacts with requestFile.
	public void transmitFile(Socket targetSocket, File file) {
		try(BufferedReader textInput = new BufferedReader(new InputStreamReader(targetSocket.getInputStream())); //This BufferedReader will buffer inputs from the socket and return lines as strings.
			OutputStream output = targetSocket.getOutputStream(); //OutputStreams let you push raw byte arrays to the socket
			PrintWriter textOutput = new PrintWriter(output);) { //PrintWriters let you push more accessible data types like strings to the socket
			
			System.out.println("Connection from " + targetSocket.getInetAddress());
			String inputLine = textInput.readLine();
			System.out.println("RECV: " + inputLine);
			Scanner lineReader = new Scanner(inputLine);
			lineReader.useDelimiter("[ <>]");
			String command = lineReader.next();
			if(!command.equals("GET")) {
				lineReader.close();
				return;
			}
			int startByte = lineReader.nextInt();
			int endByte = lineReader.nextInt();
			lineReader.close();
			if(endByte - startByte > 1024) {
				textOutput.println("<REP GET INVALID>");
				System.out.println("SEND: <REP GET INVALID>");
				textOutput.flush();
				return;
			}
			byte[] outputArray = getBytesFromFile(file, startByte, endByte);
			textOutput.println("<REP GET BEGIN>");
			System.out.println("SEND: <REP GET BEGIN>");
			textOutput.flush();
			output.write(outputArray);
			System.out.println("SEND: (DATA)");
			//System.out.println("SEND: " + new String(outputArray)); //DO NOT DO THIS. IT WILL MAKE YOUR COMPUTER LITERALLY SCREAM AT YOU FOREVER.
			output.flush();
			textOutput.println("<REP GET END>");
			System.out.println("SEND: <REP GET END>");
			textOutput.flush();
			
		} catch(IOException e) {
			e.printStackTrace();
			return;
		}
	}
	
	//Requests a piece of a file from a given peer
	public boolean requestFile(String hostname, int port, int startByte, int endByte) throws IOException {
		try(Socket requestSocket = new Socket(hostname, port); //Create a socket to the server
			InputStream input = requestSocket.getInputStream(); //Input streams input raw byte streams
			BufferedReader textInput = new BufferedReader(new InputStreamReader(requestSocket.getInputStream())); //This BufferedReader will buffer inputs from the socket and return lines as strings.
			OutputStream output = requestSocket.getOutputStream();
			PrintWriter textOutput = new PrintWriter(output); //PrintWriters let you push more accessible data types like strings to the socket
			) {
			byte[] pieceArray = new byte[endByte - startByte];
			
			textOutput.println("<GET " + startByte + " " + endByte + ">");
			textOutput.flush();
			System.out.println("SEND: <GET " + startByte + " " + endByte + ">");
			String inputLine = textInput.readLine();
			System.out.println("RECV: " + inputLine);
			if(!inputLine.equals("<REP GET BEGIN>")) {
				return false;
			}
			input.read(pieceArray);
			System.out.println("RECV: (DATA)");
			//System.out.println("RECV: " + new String(pieceArray)); //DO NOT DO THIS. IT WILL MAKE YOUR COMPUTER LITERALLY SCREAM AT YOU FOREVER.
			inputLine = textInput.readLine();
			System.out.println("RECV: " + inputLine);
			if(!inputLine.equals("<REP GET END>")) {
				return false;
			}
			
			writeBytesToFile(currFile, startByte, pieceArray);
			return true;
			
		} catch(IOException e) {
			throw e;
		}
	}
	
	//Download a file from peers, after a tracker file has been loaded
	public void downloadFile() {
		long lastUpdate = System.currentTimeMillis();
		while(!complete()) {
			scheduleRequest();
			if(System.currentTimeMillis() - lastUpdate > updatePeriod) {
				updateTracker();
				getTracker(currFile.getName(), false);
				lastUpdate = System.currentTimeMillis();
			}
		}
		System.out.println("Download complete. Verifying file...");
		byte[] calculatedMD5 = computeMD5(currFile);
		if(Arrays.equals(md5, calculatedMD5)) {
			System.out.println("File verification successful.");
			updateTracker();
		} else {
			System.out.println("Error! File verification failed.");
			System.out.println("TRACK: " + byteToHex(md5));
			System.out.println("LOCAL: " + byteToHex(calculatedMD5));
		}
		currTrackFile.delete();
		currPort += 100;
	}
	
	//Select and make the next valid request for a piece of the file, if any exist.
	public void scheduleRequest() {
		if(getEndByte() == currFileSize || currDownloads.cardinality() >= MAXIMUM_CONCURRENT_DOWNLOADS) {
			return;
		}
		int startByte = (int)getEndByte();
		for(int i = 0; i < peers.size(); i++) {
			PeerWrapper peer = peers.get(i);
			try {
				downloadsMutex.acquire();
				boolean active = currDownloads.get(i);
				downloadsMutex.release();
				if(!active && peer.endByte > startByte && !(peer.hostName.equals(InetAddress.getLocalHost().getHostAddress()) && peer.port == basePort)) {
					System.out.println("Peer found for segment " + getPieceNumber(startByte) + ": " + peer.hostName + ":" + peer.port);
					RequestHandler requester = new RequestHandler(peer.hostName, peer.port, startByte, (int) Math.min(startByte + PIECE_SIZE, currFileSize), i);
					ScheduledRequest scheduled = new ScheduledRequest(requester);
					Thread requestThread = new Thread(scheduled);
					downloadsMutex.acquire();
					currDownloads.set(i); //TODO: This SHOULD lock out any other loops through from accessing that peer, but something weird is going on. When conflicts occur weird garbage gets through, but is corrected for.
					downloadsMutex.release();
					requestThread.start();
					break;
				}
			} catch (UnknownHostException | InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
	
	//Returns true when downloading is complete.
	public boolean complete() {
		//System.out.println("DEBUG: Current end byte is " + getEndByte() + "/" + currFileSize + ". Currently downloading from " + currDownloads.cardinality() + " sources.");
		return getEndByte() == currFileSize && currDownloads.cardinality() == 0; //TODO: For some reason this is occasionally incorrect when there are multiple peers to download from.
	}
	
	//Implements the user interface as defined in the design document. Run with "java -cp bin peer.Peer". Use a different port number for different instances on the same computer.
	//To test, run the server in one window, and multiple peers with different port numbers in their own windows, and in different folders. The peer who has the files to send should type "createtracker <filename>" and it will be uploaded to the server.
	//Then, any other peers can type "GET <filename>" and they will start downloading the file. When finished, the file will be verified and usually successfully.
	public static void main(String[] args) {
		Peer peer = new Peer();
		
		peer.resumeFile();
		
		@SuppressWarnings("resource")
		Scanner userInput = new Scanner(System.in);
		while(true) {
			try {
				System.out.print("Enter command: ");
				String command = userInput.next();
				if(command.equals("REQ") && userInput.next().equals("LIST")) {
					peer.getList();
				} else if(command.equals("GET")) {
					boolean success = peer.getTracker(userInput.next(), true);
					if(!success) {
						continue;
					}
					peer.downloadFile();
				} else if(command.equals("createtracker")) {
					String fileName = userInput.next();
					if(userInput.hasNextInt()) {
						peer.createTracker(fileName, userInput.nextInt(), userInput.next(), userInput.next(), userInput.next(), userInput.nextInt());
					} else {
						peer.createTracker(fileName, userInput.next());
					}
				} else if(command.equals("updatetracker")) {
					String fileName = userInput.next();
					if(userInput.hasNextInt()) {
						peer.updateTracker(fileName, userInput.nextInt(), userInput.nextInt(), userInput.next(), userInput.nextInt());
					} else {
						peer.updateTracker();
					}
				} else {
					System.out.println("INVALID COMMAND");
				}
				userInput.nextLine();
			} catch(InputMismatchException e) {
				System.out.println("INVALID PARAMETERS");
				e.printStackTrace();
			}
		}
	}
	
	
	
	//Class which contains information about a peer.
	public static class PeerWrapper implements Comparable<PeerWrapper> {
		public String hostName;
		public int port;
		public long startByte;
		public long endByte;
		public long timeStamp;
		public boolean missing;
		
		public PeerWrapper(String hostName, int port, int startByte, int endByte, long timeStamp) {
			this.hostName = hostName;
			this.port = port;
			this.startByte = startByte;
			this.endByte = endByte;
			this.timeStamp = timeStamp;
			missing = false;
		}
		
		public int compareTo(PeerWrapper other) {
			return (int)(other.timeStamp - timeStamp);
		}
	}
	
	//Class which runs broadcastFile in a thread
	private class BroadcastHandler implements Runnable {
		private File seedFile;
		private int port;
		
		public BroadcastHandler(File seedFile, int port) {
			this.seedFile = seedFile;
			this.port = port;
		}
		
		public void run() {
			broadcastFile(seedFile, port);
		}
	}
	
	//Class which runs transmitFile in a thread
	private class TransmitHandler implements Runnable {
		private Socket targetSocket;
		private File transmitFile;
	
		public TransmitHandler(Socket targetSocket, File transmitFile) {
			this.targetSocket = targetSocket;
			this.transmitFile = transmitFile;
		}
		
		public void run() {
			transmitFile(targetSocket, transmitFile);
		}
	}
	
	//Class which runs requestFile in a thread
	private class RequestHandler implements Runnable {
		private String hostName;
		private int port;
		private int startByte;
		private int endByte;
		private int peerNumber;
		
		public RequestHandler(String hostName, int port, int startByte, int endByte, int peerNumber) {
			this.hostName = hostName;
			this.port = port;
			this.startByte = startByte;
			this.endByte = endByte;
			this.peerNumber = peerNumber;
		}

		//This is the method that runs when the thread starts.
		public void run() {
			try {
				piecesMutex.acquire();
				piecesNeeded.clear(getPieceNumber(startByte));
				piecesMutex.release();
				boolean success;
				try {
					success = requestFile(hostName, port, startByte, endByte);
				} catch (IOException e) {
					success = false;
					System.out.println("ERROR: PEER " + hostName + ":" + port + " IS MISSING.");
					peers.get(peerNumber).missing = true;
				}
				if(!success) {
					piecesMutex.acquire();
					piecesNeeded.set(getPieceNumber(startByte));
					piecesMutex.release();
				}
				downloadsMutex.acquire();
				currDownloads.clear(peerNumber);
				downloadsMutex.release();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
	
	//Uses some java concurrency features to allow download threads to time out if a download has failed
	private class ScheduledRequest implements Runnable {
		private RequestHandler requester;
		
		public ScheduledRequest(RequestHandler requester) {
			this.requester = requester;
		}
		
		public void run() {
			ExecutorService executor = Executors.newSingleThreadExecutor();
			Future<?> future = executor.submit(requester);
			try {
				future.get(5000, TimeUnit.MILLISECONDS);
				downloadsMutex.acquire();
				currDownloads.clear(requester.peerNumber);
				downloadsMutex.release();
			} catch (InterruptedException | ExecutionException | TimeoutException e) {
				System.out.println("ERROR: REQUEST TO PEER " + requester.hostName + ":" + requester.port + "  HAS TIMED OUT.");
				try {
					downloadsMutex.acquire();
					currDownloads.clear(requester.peerNumber);
					downloadsMutex.release();
				} catch (InterruptedException e1) {
					e1.printStackTrace();
				}
			}
		}
	}
}
