/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.bluetoothchat;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/**
 * This class does all the work for setting up and managing Bluetooth
 * connections with other devices. It has a thread that listens for
 * incoming connections, a thread for connecting with a device, and a
 * thread for performing data transmissions when connected.
 */
public class BluetoothChatService {
    // Debugging
    private static final String TAG = "BluetoothChatService";

    // Name for the SDP record when creating server socket
    private static final String NAME_SECURE = "BluetoothChatSecure";
    private static final String NAME_INSECURE = "BluetoothChatInsecure";

    // Unique UUID for this application
    private static final UUID MY_UUID_SECURE =
            UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66");
    private static final UUID MY_UUID_INSECURE =
            UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66");

    // Member fields
    private final BluetoothAdapter mAdapter;
    private final Handler mHandler;
    private AcceptThread mSecureAcceptThread;
    private AcceptThread mInsecureAcceptThread;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private int mState;

    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;       // we're doing nothing
    public static final int STATE_LISTEN = 1;     // now listening for incoming connections
    public static final int STATE_CONNECTING = 2; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 3;  // now connected to a remote device

    //Crypto Globals
    private BigInteger serverPrivate = null;
    private BigInteger serverPublic = null;

    private BigInteger clientPrivate = null;
    private BigInteger clientPublic = null;

    private BigInteger clientSharedKey = null;
    private BigInteger serverSharedKey = null;

    private boolean isServer = true;
    private boolean needToSendClient = false;


    /*
        Function takes client public key and writes over bluetooth socket to server for DH key exchange
     */
    public void sendClientKeyInfo(byte[] key){

        // Get the message bytes and tell the BluetoothChatService to write
        try {
            write("PUBLIC".getBytes());
            write(key);
            write("EOF".getBytes());
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }

    /*
        Computes the private and public pair for the client, and sends it's public key to server
     */
    public void ClientDHSetup(){

        clientPrivate = new BigInteger(2048, new SecureRandom());
        clientPublic = Constants.g.modPow(clientPrivate,Constants.p);

        sendClientKeyInfo(clientPublic.toByteArray());
    }


    /**
     * Constructor. Prepares a new BluetoothChat session.
     *
     * @param context The UI Activity Context
     * @param handler A Handler to send messages back to the UI Activity
     */
    public BluetoothChatService(Context context, Handler handler) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = STATE_NONE;
        mHandler = handler;
    }

    /**
     * Set the current state of the chat connection
     *
     * @param state An integer defining the current connection state
     */
    private synchronized void setState(int state) {
        mState = state;

        // Give the new state to the Handler so the UI Activity can update
        mHandler.obtainMessage(Constants.MESSAGE_STATE_CHANGE, state, -1).sendToTarget();
    }

    /**
     * Return the current connection state.
     */
    public synchronized int getState() {
        return mState;
    }

    /**
     * Start the chat service. Specifically start AcceptThread to begin a
     * session in listening (server) mode. Called by the Activity onResume()
     */
    public synchronized void start() {

        // Cancel any thread attempting to make a connection
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        setState(STATE_LISTEN);

        // Start the thread to listen on a BluetoothServerSocket
        if (mSecureAcceptThread == null) {
            mSecureAcceptThread = new AcceptThread(true);
            mSecureAcceptThread.start();
        }
        if (mInsecureAcceptThread == null) {
            mInsecureAcceptThread = new AcceptThread(false);
            mInsecureAcceptThread.start();
        }
    }

    /**
     * Start the ConnectThread to initiate a connection to a remote device.
     *
     * @param device The BluetoothDevice to connect
     * @param secure Socket Security type - Secure (true) , Insecure (false)
     */
    public synchronized void connect(BluetoothDevice device, boolean secure) {

        // Cancel any thread attempting to make a connection
        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {
                mConnectThread.cancel();
                mConnectThread = null;
            }
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        // Start the thread to connect with the given device
        mConnectThread = new ConnectThread(device, secure);
        mConnectThread.start();
        setState(STATE_CONNECTING);
    }


    /**
     * Start the ConnectedThread to begin managing a Bluetooth connection
     *
     * @param socket The BluetoothSocket on which the connection was made
     * @param device The BluetoothDevice that has been connected
     */
    public synchronized void connected(BluetoothSocket socket, BluetoothDevice
            device, final String socketType) {

        // Cancel the thread that completed the connection
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        // Cancel the accept thread because we only want to connect to one device
        if (mSecureAcceptThread != null) {
            mSecureAcceptThread.cancel();
            mSecureAcceptThread = null;
        }
        if (mInsecureAcceptThread != null) {
            mInsecureAcceptThread.cancel();
            mInsecureAcceptThread = null;
        }

        // Start the thread to manage the connection and perform transmissions
        mConnectedThread = new ConnectedThread(socket, socketType);
        mConnectedThread.start();

        // Send the name of the connected device back to the UI Activity
        Message msg = mHandler.obtainMessage(Constants.MESSAGE_DEVICE_NAME);
        Bundle bundle = new Bundle();
        bundle.putString(Constants.DEVICE_NAME, device.getName());
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        setState(STATE_CONNECTED);

    }

    /*
        Takes received server public key and computes sharedKey for the client
     */
    public BigInteger getSharedKey(byte[] serverPublic){

        BigInteger sharedKey = new BigInteger(serverPublic).modPow(clientPrivate,Constants.p);

        //Time the key for AES compatibility
        sharedKey = new BigInteger(Arrays.copyOfRange(sharedKey.toByteArray(),0,24));

        return sharedKey;
    }

    /*
        Computes DH public and private pair for server and computes shared Key
     */
    public BigInteger DHSetup(BigInteger g, BigInteger p, byte[] clientPublic){

        //Generate pair
        serverPrivate = new BigInteger(2048, new SecureRandom());
        serverPublic = Constants.g.modPow(serverPrivate,Constants.p);

        //Use client Public to generate shared key
        BigInteger sharedKey = new BigInteger(clientPublic).modPow(serverPrivate,Constants.p);

        //Trim the key for AES compatibility
        sharedKey = new BigInteger(Arrays.copyOfRange(sharedKey.toByteArray(),0,24));

        //Write server public key to client so client can compute the shared KEy
        write(serverPublic.toByteArray());

        return sharedKey;
    }


    /**
     * Stop all threads
     */
    public synchronized void stop() {

        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        if (mSecureAcceptThread != null) {
            mSecureAcceptThread.cancel();
            mSecureAcceptThread = null;
        }

        if (mInsecureAcceptThread != null) {
            mInsecureAcceptThread.cancel();
            mInsecureAcceptThread = null;
        }
        setState(STATE_NONE);
    }

    /**
     * Write to the ConnectedThread in an unsynchronized manner
     *
     * @param out The bytes to write
     * @see ConnectedThread#write(byte[])
     */
    public void write(byte[] out) {
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        // Perform the write unsynchronized
        r.write(out);
    }


    /**
     * Indicate that the connection attempt failed and notify the UI Activity.
     */
    private void connectionFailed() {
        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(Constants.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(Constants.TOAST, "Unable to connect device");
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        // Start the service over to restart listening mode
        BluetoothChatService.this.start();
    }

    /**
     * Indicate that the connection was lost and notify the UI Activity.
     */
    private void connectionLost() {
        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(Constants.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(Constants.TOAST, "Device connection was lost");
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        // Start the service over to restart listening mode
        BluetoothChatService.this.start();
    }

    /**
     * This thread runs while listening for incoming connections. It behaves
     * like a server-side client. It runs until a connection is accepted
     * (or until cancelled).
     */
    private class AcceptThread extends Thread {
        // The local server socket
        private final BluetoothServerSocket mmServerSocket;
        private String mSocketType;

        public AcceptThread(boolean secure) {
            BluetoothServerSocket tmp = null;
            mSocketType = secure ? "Secure" : "Insecure";

            // Create a new listening server socket
            try {
                if (secure) {
                    tmp = mAdapter.listenUsingRfcommWithServiceRecord(NAME_SECURE,
                            MY_UUID_SECURE);
                } else {
                    tmp = mAdapter.listenUsingInsecureRfcommWithServiceRecord(
                            NAME_INSECURE, MY_UUID_INSECURE);
                }
            } catch (IOException e) {
            }
            mmServerSocket = tmp;
            isServer = true;
        }

        public void run() {
            setName("AcceptThread" + mSocketType);

            BluetoothSocket socket = null;

            // Listen to the server socket if we're not connected
            while (mState != STATE_CONNECTED) {
                try {
                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                    socket = mmServerSocket.accept();
                } catch (IOException e) {
                    break;
                }

                // If a connection was accepted
                if (socket != null) {
                    synchronized (BluetoothChatService.this) {
                        switch (mState) {
                            case STATE_LISTEN:
                            case STATE_CONNECTING:
                                // Situation normal. Start the connected thread.
                                connected(socket, socket.getRemoteDevice(),
                                        mSocketType);
                                break;
                            case STATE_NONE:
                            case STATE_CONNECTED:
                                // Either not ready or already connected. Terminate new socket.
                                try {
                                    socket.close();
                                } catch (IOException e) {
                                }
                                break;
                        }
                    }
                }
            }
        }

        public void cancel() {
            try {
                mmServerSocket.close();
            } catch (IOException e) {

            }
        }
    }


    /**
     * This thread runs while attempting to make an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;
        private String mSocketType;

        public ConnectThread(BluetoothDevice device, boolean secure) {
            mmDevice = device;
            BluetoothSocket tmp = null;
            mSocketType = secure ? "Secure" : "Insecure";

            // Get a BluetoothSocket for a connection with the
            // given BluetoothDevice
            try {
                if (secure) {
                    tmp = device.createRfcommSocketToServiceRecord(
                            MY_UUID_SECURE);
                } else {
                    tmp = device.createInsecureRfcommSocketToServiceRecord(
                            MY_UUID_INSECURE);
                }
            } catch (IOException e) {

            }
            mmSocket = tmp;
            isServer = false;
            needToSendClient = true;

        }

        public void run() {

            setName("ConnectThread" + mSocketType);

            // Always cancel discovery because it will slow down a connection
            mAdapter.cancelDiscovery();

            // Make a connection to the BluetoothSocket
            try {
                // This is a blocking call and will only return on a
                // successful connection or an exception
                mmSocket.connect();
            } catch (IOException e) {
                // Close the socket
                try {
                    mmSocket.close();
                } catch (IOException e2) {

                }
                connectionFailed();
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (BluetoothChatService.this) {
                mConnectThread = null;
            }

            // Start the connected thread
            connected(mmSocket, mmDevice, mSocketType);
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {

            }
        }
    }

    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        private BigInteger sharedKey;

        public ConnectedThread(BluetoothSocket socket, String socketType) {

            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {

            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;

        }

        public int findArray(byte[] largeArray, byte[] subArray) {
            if (subArray.length == 0) {
                return -1;
            }
            int limit = largeArray.length - subArray.length;
            next:
            for (int i = 0; i <= limit; i++) {
                for (int j = 0; j < subArray.length; j++) {
                    if (subArray[j] != largeArray[i+j]) {
                        continue next;
                    }
                }
        /* Sub array found - return its index */
                return i;
            }
        /* Return default value */
            return -1;
        }

        public void run() {

            boolean fileNameSent = false;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // Keep listening to the InputStream while connected
            while (true) {
                try {

                    //If client public key has yet to be sent
                    if(needToSendClient){
                        //and we are not acting as "server'
                        if(isServer == false){
                            //We are server side client, so setup DH and send to server
                            ClientDHSetup();
                            //Sent client info, so let's check that off
                            needToSendClient = false;
                        }
                    }
                    //If we are not a server side client
                    if(isServer == true){
                        //And there is something to read
                        if (mmInStream.available() > 0) {
                            while (mmInStream.available() > 0) {
                                byte[] buf = new byte[mmInStream.available()];
                                mmInStream.read(buf);
                                out.write(buf);
                            }

                            //Compute our flags to check for delimitation
                            byte[] fileByte = out.toByteArray();
                            byte[] clientPub = "PUBLIC".getBytes();
                            byte[] eof = "EOF".getBytes();

                            //Get index of our public key start and end
                            int publicIndex = findArray(fileByte,clientPub);
                            int eofIndex = findArray(fileByte,eof);

                            if(publicIndex != -1 && eofIndex != -1){
                                publicIndex += 6;
                                //Extract out public key
                                byte[] publicKey = Arrays.copyOfRange(fileByte,publicIndex,eofIndex);

                                //Compute server shared key with the now obtained client public key
                                serverSharedKey = DHSetup(Constants.g, Constants.p, publicKey);

                                //Let chat fragment know of our computed key so it can be set and used for AES decrypt/encrypt
                                mHandler.obtainMessage(Constants.KEY_RECEIVED, serverSharedKey.toByteArray().length, -1, serverSharedKey.toByteArray())
                                        .sendToTarget();
                                //Clear the buffer
                                out.reset();
                            }

                        }
                    }
                    //If we are server side client and client shared key has not been computed yet
                    if(isServer == false && clientSharedKey == null){
                        if (mmInStream.available() > 0) {
                            ByteArrayOutputStream publicOut = new ByteArrayOutputStream();
                            while (mmInStream.available() > 0) {
                                byte[] buf = new byte[mmInStream.available()];
                                mmInStream.read(buf);
                                publicOut.write(buf);
                            }

                            //Obtain server public key
                            byte[] serverPublic = publicOut.toByteArray();

                            //Finish up the DH process and compute the client's shared Key
                            clientSharedKey = getSharedKey(serverPublic);

                            //Notify chat fragment so global can be set and used for AES encrypt/decrypt
                            if (clientSharedKey != null && clientSharedKey.toByteArray().length > 0) {
                                mHandler.obtainMessage(Constants.KEY_RECEIVED, clientSharedKey.toByteArray().length, -1, clientSharedKey.toByteArray())
                                        .sendToTarget();
                            }

                        }
                    }

                    //If a server or client key has been computed then we are safe to listen for a file coming in
                    if(serverSharedKey != null || clientSharedKey != null) {
                        // Read from the IlnputStream
                        if (mmInStream.available() > 0) {
                            while (mmInStream.available() > 0) {
                                byte[] buf = new byte[mmInStream.available()];
                                mmInStream.read(buf);
                                out.write(buf);
                            }
                        }

                        byte[] fileByte = out.toByteArray();

                        //make flags for buffer delimitnation
                        byte[] pay = "PAYLOAD".getBytes();
                        byte[] eof = "EOF".getBytes();

                        //get indexes for file name and payload
                        int nameIndex = findArray(fileByte, pay);
                        int eofIndex = findArray(fileByte, eof);

                        if (nameIndex != -1 && eofIndex != -1) {

                            //Get file name and put into byte array
                            int fileIndex = nameIndex + 7;
                            byte[] name = new byte[nameIndex];
                            for (int i = 0; i < nameIndex; i++) {
                                name[i] = fileByte[i];
                            }

                            //Extract out file payload
                            byte[] file = Arrays.copyOfRange(fileByte, fileIndex, eofIndex);

                            //Notify chat fragment of file name so it can be written to local storage
                            if (name != null && name.length > 0) {
                                mHandler.obtainMessage(Constants.FILE_NAME, name.length, -1, name)
                                        .sendToTarget();
                            }
                            //Notify chat fragment of received file so it can be written out to local device
                            if (file != null && file.length > 0 && fileNameSent == false) {
                                mHandler.obtainMessage(Constants.MESSAGE_READ, file.length, -1, file)
                                        .sendToTarget();
                                fileNameSent = true;
                                out.reset();
                            }
                        }
                    }
                } catch (IOException e) {

                    connectionLost();
                    // Start the service over to restart listening mode
                    BluetoothChatService.this.start();
                    break;
                }
            }
        }

        /**
         * Write to the connected OutStream.
         *
         * @param buffer The bytes to write
         */
        public void write(byte[] buffer) {
            try {
                mmOutStream.write(buffer);

//                // Share the sent message back to the UI Activity
//                mHandler.obtainMessage(Constants.MESSAGE_WRITE, -1, -1, buffer)
//                        .sendToTarget();
            } catch (IOException e) {

            }
        }

        public void cancel() {
            try {
                mmSocket.close();
                clientSharedKey = null;
            } catch (IOException e) {

            }
        }
    }
}
