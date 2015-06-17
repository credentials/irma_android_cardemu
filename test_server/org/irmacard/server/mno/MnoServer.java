package org.irmacard.server.mno;

import java.io.*;
import java.net.*;
import org.irmacard.server.mno.SubscriberDatabase;

class MnoServer
{
	private static final int SOCKET = 6789; 
	SubscriberDatabase subscribers = new MockupSubscriberDatabase ();
	DataOutputStream outToClient;
	public MnoServer (){
		this.subscribers = new MockupSubscriberDatabase ();
	}
	
	public void startServer() throws Exception{
         ServerSocket welcomeSocket = new ServerSocket(SOCKET);
         String input;

         print("MNO server started");
         while(true)
         {
            Socket connectionSocket = welcomeSocket.accept();
            BufferedReader inFromClient =
               new BufferedReader(new InputStreamReader(connectionSocket.getInputStream()));
            outToClient = new DataOutputStream(connectionSocket.getOutputStream());
            print("New incoming IRMA app connection");
			while ((input = inFromClient.readLine())!=null){
				if (input.startsWith("IMSI: ")){
					System.out.println("> connection belonging to: "+ input); 
					findSubscriber(input.substring(6));
				} else if (input.startsWith("PASP: found")){
					System.out.println("> Passport detected. Verifying ....");
				} else if (input.startsWith("PASP: verified")){
					System.out.println("> Passport Verified.");
					System.out.println("> Issueing temporary credential...");
				} else if (input.startsWith("ISSU: succesfull")){
					System.out.println("> Succesfully issued credential");
				}
	        	}
			print("Connection closed");
		}
	}

	private void findSubscriber (String imsi) throws Exception{
        SubscriberInfo subscriberInfo;
		subscriberInfo = subscribers.getSubscriber (imsi);
		if (subscriberInfo != null){
			System.out.println("> found info: " + subscriberInfo.toString());
        	outToClient.writeBytes("SI: "+subscriberInfo.toString()+'\n');
			System.out.println("> awaiting Passport....");
		} else {
			System.out.println("> No subscriber found with IMSI: " + imsi);
			outToClient.writeBytes("SI: error");
		}
	}


   	public static void main(String argv[]) throws Exception
      {
			MnoServer server = new MnoServer();
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
