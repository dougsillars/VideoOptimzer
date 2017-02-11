/*
 *  Copyright 2017 AT&T
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package com.att.aro.core.securedpacketreader.pojo;

public class SavedTLSSession {
	byte[] pSessionIDorTicket;
	byte[] master = new byte[48];
	
	public byte[] getpSessionIDorTicket() {
		return pSessionIDorTicket;
	}
	
	public void setpSessionIDorTicket(byte[] pSessionIDorTicket) {
		this.pSessionIDorTicket = pSessionIDorTicket;
	}
	
	public byte[] getMaster() {
		return master;
	}
	
	public void setMaster(byte[] master) {
		this.master = master;
	}	
}
