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

public class FileTransferTest {
	private static final int PORT = 10000;
	private static final int PIECE_SIZE = 1024;
	
	public static void broadcastFile(String fname) {
		byte[] fArray;
		try(FileInputStream fileReader = new FileInputStream(fname);) {
			fArray = new byte[fileReader.available()];
			fileReader.read(fArray);
		} catch(IOException e) {
			e.printStackTrace();
			return;
		}
		
		try(ServerSocket broadcastSocket = new ServerSocket(PORT);
			Socket targetSocket = broadcastSocket.accept();
			BufferedReader input = new BufferedReader(new InputStreamReader(targetSocket.getInputStream()));
			OutputStream output = targetSocket.getOutputStream();
			PrintWriter textOutput = new PrintWriter(output);
			) {
			String inputLine;
			byte[] outputArray;
			
			System.out.println("Connection from " + targetSocket.getInetAddress());
			
			while((inputLine = input.readLine()) != null) {
				System.out.println("Input: " + inputLine);
				Scanner lineReader = new Scanner(inputLine);
				String command = lineReader.next();
				if(command.equals("SIZE")) {
					lineReader.close();
					textOutput.println("SIZE " + fArray.length);
					textOutput.flush();
					
				} else if(command.equals("REQ")) {
					int startByte = lineReader.nextInt();
					int endByte = lineReader.nextInt();
					lineReader.close();
					
					outputArray = new byte[endByte - startByte];
					for(int i = 0; i < endByte - startByte; i++) {
						outputArray[i] = fArray[startByte + i];
					}
					output.write(outputArray);
					output.flush();
				} else if(command.equals("END")) {
					lineReader.close();
					return;
				}
			}
		} catch(IOException e) {
			e.printStackTrace();
			return;
		}
	}
	
	public static void requestFile(String fname, String hname) {
		byte[] fArray;
		
		try(Socket requestSocket = new Socket(hname, PORT);
			InputStream input = requestSocket.getInputStream();
			BufferedReader textInput = new BufferedReader(new InputStreamReader(requestSocket.getInputStream()));
			OutputStream output = requestSocket.getOutputStream();
			PrintWriter textOutput = new PrintWriter(output);
			) {
			String inputLine;
			int pointer = 0;
			
			textOutput.println("SIZE");
			textOutput.flush();
			inputLine = textInput.readLine();
			Scanner lineReader = new Scanner(inputLine);
			String reply = lineReader.next();
			fArray = new byte[lineReader.nextInt()];
			lineReader.close();
			
			while(pointer < fArray.length) {
				int endByte = Math.min(pointer + PIECE_SIZE, fArray.length);
				byte[] pieceArray = new byte[endByte - pointer];
				textOutput.println("REQ " + pointer + " " + endByte);
				textOutput.flush();
				input.read(pieceArray);
				System.out.println(Arrays.toString(pieceArray));
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
		
		try(FileOutputStream fileWriter = new FileOutputStream(fname);) {
			fileWriter.write(fArray);
		} catch(IOException e) {
			e.printStackTrace();
			return;
		}
	}
	
	public static void main(String[] args) {
		if(args.length == 1) {
			broadcastFile(args[0]);
		} else if(args.length == 2) {
			requestFile(args[0], args[1]);
		}
	}
}
