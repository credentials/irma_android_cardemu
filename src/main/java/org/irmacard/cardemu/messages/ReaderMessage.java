/*
 * Copyright (c) 2015, the IRMA Team
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 *  Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 *  Neither the name of the IRMA project nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.irmacard.cardemu.messages;

public class ReaderMessage {
	public String type = null;
	public String name = null;
	public String id = null;
	public ReaderMessageArguments arguments = null;
	
	public static String TYPE_EVENT = "event";
	public static String TYPE_COMMAND = "command";
	public static String TYPE_RESPONSE = "response";
	
	public static String NAME_COMMAND_AUTHPIN = "authorizeWithPin";
	public static String NAME_COMMAND_TRANSMIT = "transmitCommandSet";
	public static String NAME_COMMAND_SELECTAPPLET = "selectApplet";
	public static String NAME_COMMAND_IDLE = "idle";

	public static String NAME_EVENT_CARDFOUND = "cardInserted";
	public static String NAME_EVENT_CARDLOST = "cardRemoved";
	public static String NAME_EVENT_CARDREADERFOUND = "cardReaderFound";
	public static String NAME_EVENT_STATUSUPDATE = "statusUpdate";
	public static String NAME_EVENT_TIMEOUT = "timeout";
	public static String NAME_EVENT_DONE = "done";

	public ReaderMessage(String type, String name) {
		this.type = type;
		this.name = name;
	}
	
	public ReaderMessage(String type, String name, String id) {
		this.type = type;
		this.name = name;
		this.id = id;
	}
	
	public ReaderMessage(String type, String name, String id, ReaderMessageArguments arguments) {
		this.type = type;
		this.name = name;
		this.id = id;
		this.arguments = arguments;		
	}

	public String toString() {
		return "<Type: " + type + ", name: " + name + ", id: " + id + ", arguments: " + arguments.toString() + ">";
	}
}
