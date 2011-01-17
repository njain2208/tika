/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.fork;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

class ForkServer extends ClassLoader {

    public static final byte ERROR = -1;

    public static final byte REPLY = 0;

    public static final byte ECHO = 1;

    public static final byte RESOURCE = 2;

    /**
     * Starts a forked server process.
     * 
     * @param args command line arguments, ignored
     * @throws Exception if the server could not be started
     */
    public static void main(String[] args) throws Exception {
        ForkServer server =
            new ForkServer(System.in, System.out);

        // Set the server instance as the context class loader
        // to make classes from the parent process available
        Thread.currentThread().setContextClassLoader(server);

        // Redirect standard input and output streams to prevent
        // stray code from interfering with the message stream
        System.setIn(new ByteArrayInputStream(new byte[0]));
        System.setOut(System.err);

        // Start processing request
        server.run();
    }

    private final DataInputStream input;

    private final DataOutputStream output;

    private int count = 0;

    /**
     * Sets up a forked server instance using the given stdin/out
     * communication channel.
     *
     * @param input input stream for reading from the parent process
     * @param output output stream for writing to the parent process
     * @throws IOException if the server instance could not be created
     */
    public ForkServer(InputStream input, OutputStream output)
            throws IOException {
        this.input = new DataInputStream(input);
        this.output = new DataOutputStream(output);
    }

    public void run() throws IOException {
        int b;
        while ((b = input.read()) != -1) {
            if (b == ECHO) {
                try {
                    Object message =
                        ForkSerializer.deserialize(input, this);
                    output.write(ECHO);
                    ForkSerializer.serialize(output, "echo: " + message);
                } catch (ClassNotFoundException e) {
                    output.write(ERROR);
                    ForkSerializer.serialize(output, e);
                }
                output.flush();
            }
        }
    }

    @Override
    protected synchronized URL findResource(String name) {
        try {
            // Send a request to load the resource data
            output.write(RESOURCE);
            output.write(0);
            output.write(1);
            output.writeUTF(name);
            output.flush();

            // Receive the response
            if (input.readBoolean()) {
                return readStreamToFile().toURI().toURL();
            } else {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    protected synchronized Enumeration<URL> findResources(String name)
            throws IOException {
        // Send a request to load the resources
        output.write(RESOURCE);
        output.write(0);
        output.write(2);
        output.writeUTF(name);
        output.flush();

        // Receive the response
        List<URL> resources = new ArrayList<URL>();
        while (input.readBoolean()) {
            resources.add(readStreamToFile().toURI().toURL());
        }
        return Collections.enumeration(resources);
    }

    @Override
    protected synchronized Class<?> findClass(String name)
            throws ClassNotFoundException {
        try {
            // Send a request to load the class data
            output.write(RESOURCE);
            output.write(0);
            output.write(1);
            output.writeUTF(name.replace('.', '/') + ".class");
            output.flush();

            // Receive the response
            if (input.readBoolean()) {
                byte[] data = readStreamToMemory();
                return defineClass(name, data, 0, data.length);
            } else {
                return null;
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw new ClassNotFoundException("Unable load class " + name, e);
        }
    }

    private byte[] readStreamToMemory() throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        byte[] buffer = new byte[0xffff];
        int n;
        while ((n = input.readUnsignedShort()) > 0) {
            input.readFully(buffer, 0, n);
            stream.write(buffer, 0, n);
        }
        return stream.toByteArray();
    }

    private File readStreamToFile() throws IOException {
        File file = new File("resource-" + count++ + ".bin");

        OutputStream stream = new FileOutputStream(file);
        try {
            byte[] buffer = new byte[0xffff];
            int n;
            while ((n = input.readUnsignedShort()) > 0) {
                input.readFully(buffer, 0, n);
                stream.write(buffer, 0, n);
            }
        } finally {
            stream.close();
        }

        return file;
    }

}
