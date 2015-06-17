package org.irmacard.server.government;

import java.io.*;
import java.net.*;
import org.irmacard.server.government.*;

class GovServer
{
	private static final int SOCKET = 6788; 
	private PersonalRecordDatabase personalRecordDatabase = new MockupPersonalRecordDatabase ();
	DataOutputStream outToClient;

	
	public void startServer() throws Exception{
         ServerSocket welcomeSocket = new ServerSocket(SOCKET);
         String input;

         print("Government server started");
         while(true)
         {
            Socket connectionSocket = welcomeSocket.accept();
            BufferedReader inFromClient =
               new BufferedReader(new InputStreamReader(connectionSocket.getInputStream()));
            outToClient = new DataOutputStream(connectionSocket.getOutputStream());
            print("New incoming IRMA app connection");
			while ((input = inFromClient.readLine())!=null){
//            	System.out.println("> Received: " + input);
				if (input.startsWith("VERIFY:")){
                                        System.out.println("> Receiving documentnumber credential. Verifying.....");
				} else if (input.startsWith("SUCCESS")){
					System.out.println("> Document number credential valid! Documentnumber: NXC36R8J1");
					System.out.println("> Matching document number against database of stolen document ... No Macth!");
					System.out.println("> Issueing Credentials.....");
				} else if (input.startsWith("DOCN: ")){
					findPersonalRecord(input.substring(6));
				} else if (input.startsWith("ISSUE")){
					System.out.println("> successfully issued credentials: Root, Lower Age limits");
				}
	        	}
			print("Connection closed");
		}
	}

	private void findPersonalRecord (String pasp) throws Exception{
        	PersonalRecord personalRecord;
		personalRecord = personalRecordDatabase.getPersonalRecord (pasp);
		if (personalRecord != null){
			System.out.println("> matching passport number against stolen id database....... NO Match!");
			System.out.println("> retrieving personal record......");
			System.out.println("> Found match: "+ personalRecord.toString());
        		outToClient.writeBytes("PR: "+personalRecord.toString()+'\n');
		} else {
			System.out.println("> No personal record found for passport number: " + pasp);
			outToClient.writeBytes("PR: error");
		}
	}


   	public static void main(String argv[]) throws Exception
      {
			GovServer server = new GovServer();
			server.startServer();
      }


   public static void print (String s){
       for (int i=0; i<=s.length()+4;i++){
            System.out.print("*");
        }
       System.out.println();
       System.out.println("* " + s + " *");
       for (int i=0; i<=s.length()+4;i++){
           System.out.print("*");
       }
		System.out.println();
		System.out.println();
    }
}
