/*
 Copyright 2014 AT&T
 
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
 
       http://www.apache.org/licenses/LICENSE-2.0
 
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package com.att.aro.core.packetreader.pojo;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

import com.att.aro.core.util.ForwardSecrecyUtil;
import com.att.aro.core.util.WeakCipherUtil;

/**
 * A bean class that provides access to TCP packet data.
 */
public class TCPPacket extends IPPacket implements Serializable {
	
	private static final long serialVersionUID = 1L;

	private static final byte TLS_CHANGE_CIPHER_SPEC = 20;
	private static final byte TLS_ALERT = 21;
	private static final byte TLS_HANDSHAKE = 22;
	private static final byte TLS_APPLICATION = 23;
	private static final byte TLS_CLIENT_HELLO = 1;
	private static final byte TLS_SERVER_HELLO = 2;
	
	private int sourcePort;
	private int destinationPort;
	private long sequenceNumber;
	private long ackNumber;
	private boolean urg; //URG;
	private boolean ack;//ACK;
	private boolean psh;//PSH;
	private boolean rst;//RST;
	private boolean syn;//SYN;
	private boolean fin;//FIN;
	private int window;
	private short urgentPointer;
	private int dataOffset;
	private int payloadLen;
	
	private boolean ssl;
	private boolean sslHandshake;
	private boolean sslApplicationData;
	private Set<String> unsecureSSLVersions;
	private Set<String> weakCipherSuites;
	private String selectedCipherSuite;

	/**
	 * Creates a new instance of the TCPPacket class using the specified parameters.
	 * @param seconds The number of seconds for the TCP packet.
	 * @param microSeconds The number of microseconds for the TCP packet.
	 * @param len The length of the data portion of the TCP packet (in bytes).
	 * @param datalinkHdrLen The length of the header portion of the TCP packet (in bytes).
	 * @param data An array of bytes that is the data portion of the TCP packet.
	 */
	public TCPPacket(long seconds, long microSeconds, int len, int datalinkHdrLen,
			byte[] data) {
		super(seconds, microSeconds, len, datalinkHdrLen, data);

		int headerOffset = super.getDataOffset();

		ByteBuffer bytes = ByteBuffer.wrap(data);
		sourcePort = bytes.getShort(headerOffset) & 0xFFFF;
		destinationPort = bytes.getShort(headerOffset + 2) & 0xFFFF;
		sequenceNumber = bytes.getInt(headerOffset + 4) & 0xFFFFFFFFL;
		ackNumber = bytes.getInt(headerOffset + 8) & 0xFFFFFFFFL;
		int hlen = ((bytes.get(headerOffset + 12) & 0xF0) >> 2);
		dataOffset = headerOffset + hlen;
		payloadLen = super.getPayloadLen() - hlen;
		short ivalue = bytes.getShort(headerOffset + 12);
		urg = (ivalue & 0x0020) != 0;
		ack = (ivalue & 0x0010) != 0;
		psh = (ivalue & 0x0008) != 0;
		rst = (ivalue & 0x0004) != 0;
		syn = (ivalue & 0x0002) != 0;
		fin = (ivalue & 0x0001) != 0;
		window = bytes.getShort(headerOffset + 14) & 0xFFFF;
		urgentPointer = bytes.getShort(headerOffset + 18);
		unsecureSSLVersions = new HashSet<>();
		weakCipherSuites = new HashSet<>();
		
		int offset = dataOffset;
		do {
			offset = parseSecureSocketsLayer(bytes, offset);
		} while (offset >= 0);
	}

	public Set<String> getUnsecureSSLVersions() {
		return unsecureSSLVersions;
	}

	public Set<String> getWeakCipherSuites() {
		return weakCipherSuites;
	}
	
	public String getSelectedCipherSuite() {
		return selectedCipherSuite;
	}

	/**
	 * @return The offset within the data array of the packet data excluding the header information.
	 * @see com.att.aro.pcap.IPPacket#getDataOffset()
	 */
	@Override
	public int getDataOffset() {
		return dataOffset;
	}

	/**
	 * @see com.att.aro.pcap.IPPacket#getPayloadLen()
	 */
	@Override
	public int getPayloadLen() {
		return payloadLen;
	}

	/**
	 * Gets the source port number.
	 * 
	 * @return An int value that is the source port number.
	 */
	public int getSourcePort() {
		return sourcePort;
	}

	/**
	 * Gets the destination port number.
	 * 
	 * @return An int value that is the destination port number.
	 */
	public int getDestinationPort() {
		return destinationPort;
	}

	/**
	 * Gets the sequence number.
	 * 
	 * @return A long value that is the sequence number.
	 */
	public long getSequenceNumber() {
		return sequenceNumber;
	}

	/**
	 * Gets the acknowledgment number.
	 * 
	 * @return A long value that is the acknowledgement number.
	 */
	public long getAckNumber() {
		return ackNumber;
	}

	/**
	 * Gets the Urgent (URG) flag that prioritizes certain data in a packet
	 * segment.
	 * 
	 * @return true if prioritize are set with in the packet else it is false.
	 */
	public boolean isURG() {
		return urg;
	}

	/**
	 * Gets the Acknowledge Flag.
	 * 
	 * @return A boolean value that is "true" if data is prioritized within the
	 *         packet, and is "false" otherwise.
	 */
	public boolean isACK() {
		return ack;
	}

	/**
	 * Gets the PSH flag. The PSH flag in the TCP header, informs the receiving
	 * host that the data should be pushed up to the receiving application
	 * immediately.
	 * 
	 * @return A boolean value that is "true" if the data should be pushed to
	 *         the receiving application immediately, and is "false" if a push
	 *         is not required.
	 */
	public boolean isPSH() {
		return psh;
	}

	/**
	 * Gets the RST flag. The RST flag indicates whether a connection should be
	 * aborted in response to an error.
	 * 
	 * @return A boolean value that is "true" if the connection should be closed
	 *         in response to an error, and is false if it should not.
	 */
	public boolean isRST() {
		return rst;
	}

	/**
	 * Gets a flag that indicates whether a connection should be initiated.
	 * 
	 * @return A boolean value that is true if a connection should be initiated,
	 *         and is "false" if a connection won't be initiated.
	 */
	public boolean isSYN() {
		return syn;
	}

	/**
	 * Gets the FIN Flag.
	 * 
	 * @return A boolean value that is "true" if the connection should be
	 *         closed, and is "false" if the connection should remain the same.
	 */
	public boolean isFIN() {
		return fin;
	}

	/**
	 * Gets the window.
	 * 
	 * @return An int value that is the window.
	 */
	public int getWindow() {
		return window;
	}

	/**
	 * Gets the urgent pointer that indicates priority data.
	 * 
	 * @return A short value that is the urgent pointer.
	 */
	public short getUrgentPointer() {
		return urgentPointer;
	}

	/**
	 * Indicates whether this packet contains SSL
	 * @return the ssl
	 */
	public boolean isSsl() {
		return ssl;
	}

	/**
	 * Indicates whether this packet contains SSL handshake records
	 * @return the sslHandshake
	 */
	public boolean isSslHandshake() {
		return sslHandshake;
	}

	/**
	 * Indicates whether this packet contains SSL application data
	 * @return the sslApplicationData
	 */
	public boolean isSslApplicationData() {
		return sslApplicationData;
	}

	/**
	 * Utility method that looks for TLS records in the TCP packet data
	 * @param bytes
	 * @param offset
	 * @return
	 */
	private int parseSecureSocketsLayer(ByteBuffer bytes, int offset) {

		if (bytes.array().length >= offset + 5) {
			
			// Check for TLS/SSL
			bytes.position(offset);
			byte contentType = bytes.get();
			byte majorVersion = bytes.get();
			byte minorVersion = bytes.get();
			short tlsLen = bytes.getShort();
			int result = offset + 5 + tlsLen;
			
			// sniff client hello and server hello for ciphers
			if (isHandshake(contentType, majorVersion, minorVersion)) {
				// check unsecure SSL version
				if (isBadSSLVersion(majorVersion, minorVersion)) {
					unsecureSSLVersions.add(getTLSVersion(majorVersion, minorVersion));
				}
				getCipherSuitesFromClientHello(bytes, tlsLen);
				getCipherSuiteFromServerHello(bytes, tlsLen);
			}
			
			if (isSecureSSLVersion(majorVersion, minorVersion) 
					&& (contentType == TLS_CHANGE_CIPHER_SPEC
							|| contentType == TLS_ALERT
							|| contentType == TLS_HANDSHAKE || contentType == TLS_APPLICATION)
					&& bytes.array().length >= result) {
				this.ssl = true;
				if (contentType == TLS_HANDSHAKE) {
					this.sslHandshake = true;
				} else if (contentType == TLS_APPLICATION) {
					this.sslApplicationData = true;
				}
				return result;
			}
		}
		return -1;
	}
	
	/**
	 * check if the packet contains handshake
	 * @param contentType
	 * @param majorVersion
	 * @param minorVersion
	 * @return
	 */
	private boolean isHandshake(byte contentType, byte majorVersion, byte minorVersion) {
		return contentType == TLS_HANDSHAKE 
				&& (isSecureSSLVersion(majorVersion, minorVersion) || isBadSSLVersion(majorVersion, minorVersion));
	}
	
	/**
	 * TLS 1.0, TLS 1.1, TLS 1.2 are secure TLS version
	 * TLS 1.0 - 0x0301
	 * TLS 1.1 - 0x0302
	 * TLS 1.2 - 0x0303
	 * 
	 * @param majorVersion
	 * @param minorVersion
	 * @return
	 */
	private boolean isSecureSSLVersion(byte majorVersion, byte minorVersion) {
		return majorVersion == 3 && (minorVersion >= 1 && minorVersion <= 3);
	}
	
	/**
	 * SSL 3.0, 2.0, 1.0 are insecure SSL version
	 * SSL 3.0 - 0x0300
	 * SSL 2.0 - 0x0002
	 * SSL 1.0 - ???
	 * 
	 * @param majorVersion
	 * @param minorVersion
	 * @return
	 */
	private boolean isBadSSLVersion(byte majorVersion, byte minorVersion) {
		return (majorVersion == 3 && minorVersion == 0)
				|| (majorVersion == 0 && (minorVersion == 2 || minorVersion == 1));
	}
	
	/**
	 * get TLS version [majorVersion].[minorVersion], e.g. 0x0300 will be represented as 3.0, which is SSL 3.0
	 * @param majorVersion
	 * @param minorVersion
	 * @return
	 */
	private String getTLSVersion(byte majorVersion, byte minorVersion) {
		return String.valueOf(majorVersion) + "." + String.valueOf(minorVersion);
	}
	
	/**
	 * get Cipher Suites from Client Hello
	 * @param bytes
	 */
	private void getCipherSuitesFromClientHello(ByteBuffer bytes, short tlsLen) {
		int oldPosition = bytes.position();
		try {
			if (bytes.position() < bytes.limit()) {
				byte handshakeType = bytes.get();
				if (handshakeType != TLS_CLIENT_HELLO) {
					return;
				}
			}
			
			skip(bytes, 3);		// skip length of client hello
			skip(bytes, 2);		// skip TLS version
			skip(bytes, 32);	// skip random
			int lengthOfSessionID = getSessionIDLength(bytes);	// skip length of session ID
			skip(bytes, lengthOfSessionID);	// skip session ID if session ID is NOT empty
			int lengthOfCipherSuites = getCipherSuiteLength(bytes);	// skip length of cipher suites
			setCipherSuites(bytes, lengthOfCipherSuites);
		} catch (Exception e) {
			
		} finally {
			bytes.position(oldPosition);
		}
	}
	
	/**
	 * get selected cipher suite from server hello
	 * @param bytes
	 * @param tlsLen
	 */
	private void getCipherSuiteFromServerHello(ByteBuffer bytes, short tlsLen) {
		int oldPosition = bytes.position();
		try {
			if (bytes.position() < bytes.limit()) {
				byte handshakeType = bytes.get();
				if (handshakeType != TLS_SERVER_HELLO) {
					return;
				}
			}
			
			skip(bytes, 3);		// skip length of server hello
			skip(bytes, 2);		// skip TLS version
			skip(bytes, 32);	// skip random
			int lengthOfSessionID = getSessionIDLength(bytes);	// skip length of session ID
			skip(bytes, lengthOfSessionID);	// skip session ID if session ID is NOT empty
			setCipherSuite(bytes);
		}catch (Exception e) {
			
		} finally {
			bytes.position(oldPosition);
		}
	}
	
	/**
	 * skip bytes
	 * @param bytes
	 * @param length
	 */
	private void skip(ByteBuffer bytes, int length) {
		for(int i = 0; i < length; i++) {
			if (bytes.position() >= bytes.limit()) {
				break;
			}
			bytes.get();
		}
	}
	
	/**
	 * get length of session ID
	 * @param bytes
	 * @return
	 */
	private int getSessionIDLength(ByteBuffer bytes) {
		byte[] bytesForLength = new byte[1];
		bytesForLength[0] = bytes.get();
		return getLength(bytesForLength);
	}
	
	/**
	 * get length of cipher suites
	 * @param bytes
	 * @return
	 */
	private int getCipherSuiteLength(ByteBuffer bytes) {
		byte[] bytesForLength = new byte[2];
		bytesForLength[0] = bytes.get();
		bytesForLength[1] = bytes.get();
		return getLength(bytesForLength);
	}
	
	/**
	 * get length from byte array
	 * @param length
	 * @return
	 */
	private int getLength(byte[] bytes) {
		int length = 0;
		for(int i = 0; i < bytes.length; i++) {
			int shift = 8 * (bytes.length - i - 1);
			length += bytes[i] << shift;
		}
		return length;
	}
	
	/**
	 * get cipher suites from client hello
	 * @param bytes
	 * @param length
	 * @return
	 */
	private void setCipherSuites(ByteBuffer bytes, int length) {
		for(int i = 0; i < length; i+=2) {
			byte[] cipherSuite = new byte[2];
			cipherSuite[0] = bytes.get();
			cipherSuite[1] = bytes.get();
			String cipherHex = getHexCipherSuite(cipherSuite);
			if (WeakCipherUtil.containsKey(cipherHex)) {
				weakCipherSuites.add(cipherHex);
			}
		}
	}
	
	/**
	 * get cipher suite from server hello
	 * @param bytes
	 */
	private void setCipherSuite(ByteBuffer bytes) {
		byte[] cipherSuite = new byte[2];
		cipherSuite[0] = bytes.get();
		cipherSuite[1] = bytes.get();
		String cipherHex = getHexCipherSuite(cipherSuite);
		if (ForwardSecrecyUtil.containsKey(cipherHex)) {
			selectedCipherSuite = cipherHex;
		}
	}
	
	/**
	 * get cipher suite string in hex format
	 * @param bytes
	 * @return
	 */
	private String getHexCipherSuite(byte[] bytes) {
		String a = String.format("%02x", Byte.parseByte(String.valueOf(bytes[0])));
		String b = String.format("%02x", Byte.parseByte(String.valueOf(bytes[1])));
		return "0x" + a + b;
	}
	
	/**
	 * The method is very similar to {@link #isSsl() isSsl()}.
	 * The difference between the two methods is 
	 * {@link #isSsl() isSsl()} returns true if there is a SSL 
	 * record with TLS version 1.0, 1.1 or 1.2 detected in the 
	 * packet and returns false otherwise, whereas this method 
	 * returns true if there is a SSL record with <b>any</b> 
	 * SSL or TLS version detected in the packet and returns 
	 * false otherwise.
	 * 
	 * @return true if a SSL record with any SSL or TLS version 
	 * is detected in the packet, false otherwise.
	 */
	/*
	 * This method is meant to be in this class temporarily 
	 * only. It will be moved to a utility class.
	 */
	public boolean containsSSLRecord() {
		return ssl || unsecureSSLVersions.size() > 0; 
	}
}