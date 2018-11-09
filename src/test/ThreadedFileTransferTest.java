package test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Scanner;

public class ThreadedFileTransferTest {
	private static final int PORT = 10000;
	private static final int PIECE_SIZE = 1024;
	
	//Main loop of the server. Waits for connections and sends the file to them.
	public static void broadcastFile(String fname) {
		byte[] fArray; //Store the bytes of the file in here
		try(FileInputStream fileReader = new FileInputStream(fname);) { //FileInputStreams read the bytes of a file.
			fArray = new byte[fileReader.available()];
			fileReader.read(fArray);
		} catch(IOException e) {
			e.printStackTrace();
			return;
		}
		
		try(ServerSocket broadcastSocket = new ServerSocket(PORT);){ //ServerSockets are sockets that can accept multiple connections on the same port.
			while(true) {
				Socket tgtSocket = broadcastSocket.accept();
				TransmitHandler transmitter = new TransmitHandler(fArray, tgtSocket);
				Thread transmitThread = new Thread(transmitter);
				transmitThread.start(); //Start a new thread which is running the transmitThread method.
			}
		} catch(IOException e) {
			e.printStackTrace();
			return;
		}
	}
	
	//Serverside protocol to send a file to a target machine. Interacts with requestFile.
	public static void transmitFile(byte[] fArray, Socket tgtSocket) {
		try(BufferedReader input = new BufferedReader(new InputStreamReader(tgtSocket.getInputStream())); //This BufferedReader will buffer inputs from the socket and return lines as strings.
			OutputStream output = tgtSocket.getOutputStream(); //OutputStreams let you push raw byte arrays to the socket
			PrintWriter textOutput = new PrintWriter(output); //PrintWriters let you push more accessible data types like strings to the socket
			) {
			String inputLine;
			byte[] outputArray;
			
			System.out.println("Connection from " + tgtSocket.getInetAddress());
			
			while((inputLine = input.readLine()) != null) { //Keep reading lines from the socket
				System.out.println("Input: " + inputLine);
				Scanner lineReader = new Scanner(inputLine);
				String command = lineReader.next();
				if(command.equals("SIZE")) { //If the requester requests "SIZE", then return "SIZE [length of file array]"
					lineReader.close();
					textOutput.println("SIZE " + fArray.length);
					textOutput.flush();
					
				} else if(command.equals("REQ")) { //If the requester requests "REQ [startByte] [endByte]", then return a byte array containing the requested subarray from the file array.
					int startByte = lineReader.nextInt();
					int endByte = lineReader.nextInt();
					lineReader.close();
					
					outputArray = new byte[endByte - startByte];
					for(int i = 0; i < endByte - startByte; i++) {
						outputArray[i] = fArray[startByte + i];
					}
					output.write(outputArray);
					output.flush();
				} else if(command.equals("END")) { //If the requester requests "END" then close the connection
					lineReader.close();
					return;
				}
			}
		} catch(IOException e) {
			e.printStackTrace();
			return;
		}
	}
	
	//Clientside protocol to request a file from the server. Interacts with transmitFile.
	public static void requestFile(String fname, String hname) {
		byte[] fArray;
		
		try(Socket requestSocket = new Socket(hname, PORT); //Create a socket to the server
			InputStream input = requestSocket.getInputStream(); //Input streams input raw byte streams
			BufferedReader textInput = new BufferedReader(new InputStreamReader(requestSocket.getInputStream())); //This BufferedReader will buffer inputs from the socket and return lines as strings.
			OutputStream output = requestSocket.getOutputStream();
			PrintWriter textOutput = new PrintWriter(output); //PrintWriters let you push more accessible data types like strings to the socket
			) {
			String inputLine;
			int pointer = 0;
			int completion = 1;
			
			textOutput.println("SIZE"); //Request the size of the file array
			textOutput.flush();
			inputLine = textInput.readLine();
			Scanner lineReader = new Scanner(inputLine);
			String reply = lineReader.next();
			fArray = new byte[lineReader.nextInt()]; //Initialize the file array to the size returned by the server
			lineReader.close();
			
			while(pointer < fArray.length) { //Repeatedly request pieces of the file and fill them in in order
				int endByte = Math.min(pointer + PIECE_SIZE, fArray.length);
				byte[] pieceArray = new byte[endByte - pointer];
				textOutput.println("REQ " + pointer + " " + endByte);
				textOutput.flush();
				input.read(pieceArray);
				
				//Various ways to output progress, these aren't needed for anything to work
				System.out.println(Arrays.toString(pieceArray)); //Various ways to output progress, these aren't needed for anything to work
				//System.out.println((100 * (double)pointer / fArray.length) + "% complete.");
				double exactCompletion = 100 * (double)pointer / fArray.length;
				if(exactCompletion > completion) {
					completion = (int)Math.ceil(exactCompletion);
					System.out.println(completion + "% complete.");
				}
				
				//Copy the data into the file array
				for(int i = 0; i < pieceArray.length; i++) {
					fArray[pointer + i] = pieceArray[i];
				}
				pointer = endByte;
			}
			
			textOutput.println("END");
			textOutput.flush();
		} catch(IOException e) {
			e.printStackTrace();
			return;
		}
		
		try(FileOutputStream fileWriter = new FileOutputStream(fname);) { //FileOutputStream writes an array of bytes to a file
			fileWriter.write(fArray);
		} catch(IOException e) {
			e.printStackTrace();
			return;
		}
	}
	
	//First argument is the filename to read from or write to, second is defines the ip of the server for clients.
	public static void main(String[] args) {
		if(args.length == 1) {
			broadcastFile(args[0]);
		} else if(args.length == 2) {
			requestFile(args[0], args[1]);
		}
	}
	
	//TransmitHandler enables running transmitFile in a separate thread by using the Runnable interface.
	private static class TransmitHandler implements Runnable {
		byte[] fArray;
		Socket tgtSocket;
	
		public TransmitHandler(byte[] fArray, Socket tgtSocket) {
			this.fArray = fArray;
			this.tgtSocket = tgtSocket;
		}
		
		//This is the method that runs when the thread starts.
		public void run() {
			transmitFile(fArray, tgtSocket);
		}
	}
}
