/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.util;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;

public class HttpExceptionUtils {

    private static final String ENTER = System.getProperty("line.separator");

    private static String getOneLineMessage(Throwable exception) {
        String message = exception.getMessage();
        if (message != null) {
            int i = message.indexOf(ENTER);
            if (i > -1) {
                message = message.substring(0, i);
            }
        }

        return message;
    }

    public static Response createJerseyExceptionResponse(Response.Status status, Throwable ex) {
        Map<String, Object> json = new LinkedHashMap();
        json.put("message", getOneLineMessage(ex));
        json.put("exception", ex.getClass().getSimpleName());
        json.put("javaClassName", ex.getClass().getName());
        Map<String, Object> response = new LinkedHashMap();
        response.put("RemoteException", json);
        return Response.status(status).type("application/json").entity(response).build();
    }

    public static void createServletExceptionResponse(HttpServletResponse response, int status, Throwable ex) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        Map<String, Object> json = new LinkedHashMap();
        json.put("message", getOneLineMessage(ex));
        json.put("exception", ex.getClass().getSimpleName());
        json.put("javaClassName", ex.getClass().getName());
        Map<String, Object> jsonResponse = new LinkedHashMap();
        jsonResponse.put("RemoteException", json);
        Writer writer = response.getWriter();
        JsonSerialization.writer().writeValue(writer, jsonResponse);
        writer.flush();
    }

}