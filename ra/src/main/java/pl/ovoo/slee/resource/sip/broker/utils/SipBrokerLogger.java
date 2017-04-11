/*
 * SIP Service Broker Resource Adaptor
 * Copyright (C) 2016-2017 "OVOO Sp. z o.o."
 *
 * This file is part of the SIP Service Broker RA.
 *
 * SIP Service Broker RA is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * SIP Service Broker RA is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package pl.ovoo.slee.resource.sip.broker.utils;

import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.helpers.MessageFormatter;

import javax.slee.facilities.Tracer;

/**
 * This is the JSLEE Tracer facility wrapper.
 * It offers slf4j facade interface and adds session ID in each trace.
 */
public class SipBrokerLogger implements Logger {
    private final Tracer tracer;
    private final String sessionId;

    public SipBrokerLogger(Tracer wrapped, String sessionId){
        this.tracer = wrapped;
        if(sessionId != null){
            this.sessionId = "Session:" + sessionId + ",Msg:";
        } else {
            this.sessionId = "Msg:";
        }
    }


    public String getName() {
        return tracer.getTracerName();
    }

    // Trace (Finest) trace methods
    public boolean isTraceEnabled() {
        return tracer.isFinestEnabled();
    }

    public void trace(String s) {
        if(tracer.isFinestEnabled()) {
            tracer.finest(sessionId + s);
        }
    }

    public void trace(String s, Object o) {
        if(tracer.isFinestEnabled()){
            String formattedMessage = MessageFormatter.format(sessionId + s, o);
            tracer.finest(formattedMessage);
        }
    }

    public void trace(String s, Object o, Object o1) {
        if(tracer.isFinestEnabled()) {
            String formattedMessage = MessageFormatter.format(sessionId + s, o, o1);
            tracer.finest(formattedMessage);
        }
    }

    public void trace(String s, Object... objects) {
        if(tracer.isFinestEnabled()) {
            String formattedMessage = MessageFormatter.arrayFormat(sessionId + s, objects);
            tracer.finest(formattedMessage);
        }
    }

    public void trace(String s, Throwable throwable) {
        if(tracer.isFinestEnabled()) {
            tracer.finest(sessionId + s, throwable);
        }
    }



    // Debug (Fine) trace methods
    public boolean isDebugEnabled() {
        return tracer.isFineEnabled();
    }

    public void debug(String s) {
        if(tracer.isFineEnabled()){
            tracer.fine(sessionId + s);
        }
    }

    public void debug(String s, Object o) {
        if(tracer.isFineEnabled()){
            String formattedMessage = MessageFormatter.format(sessionId + s, o);
            tracer.fine(formattedMessage);
        }
    }

    public void debug(String s, Object o, Object o1) {
        if(tracer.isFineEnabled()){
            String formattedMessage = MessageFormatter.format(sessionId + s, o, o1);
            tracer.fine(formattedMessage);
        }
    }

    public void debug(String s, Object... objects) {
        if(tracer.isFineEnabled()){
            String formattedMessage = MessageFormatter.arrayFormat(sessionId + s, objects);
            tracer.fine(formattedMessage);
        }
    }

    public void debug(String s, Throwable throwable) {
        if(tracer.isFineEnabled()){
            tracer.fine(sessionId + s, throwable);
        }
    }


    // Info trace methods
    public boolean isInfoEnabled() {
        return tracer.isInfoEnabled();
    }

    public void info(String s) {
        tracer.info(s);
    }

    public void info(String s, Object o) {
        if(tracer.isInfoEnabled()){
            String formattedMessage = MessageFormatter.format(sessionId + s, o);
            tracer.info(formattedMessage);
        }
    }

    public void info(String s, Object o, Object o1) {
        if(tracer.isInfoEnabled()){
            String formattedMessage = MessageFormatter.format(sessionId + s, o, o1);
            tracer.info(formattedMessage);
        }
    }

    public void info(String s, Object... objects) {
        if(tracer.isInfoEnabled()){
            String formattedMessage = MessageFormatter.arrayFormat(sessionId + s, objects);
            tracer.info(formattedMessage);
        }
    }

    public void info(String s, Throwable throwable) {
        if(tracer.isInfoEnabled()){
            tracer.info(sessionId + s, throwable);
        }
    }


    // Warn (Warning) trace methods
    public boolean isWarnEnabled() {
        return tracer.isWarningEnabled();
    }

    public void warn(String s) {
        tracer.warning(sessionId + s);
    }

    public void warn(String s, Object o) {
        if(tracer.isWarningEnabled()){
            String formattedMessage = MessageFormatter.format(sessionId + s, o);
            tracer.warning(formattedMessage);
        }
    }

    public void warn(String s, Object... objects) {
        if(tracer.isWarningEnabled()){
            String formattedMessage = MessageFormatter.arrayFormat(sessionId + s, objects);
            tracer.warning(formattedMessage);
        }
    }

    public void warn(String s, Object o, Object o1) {
        if(tracer.isWarningEnabled()){
            String formattedMessage = MessageFormatter.format(sessionId + s, o, o1);
            tracer.warning(formattedMessage);
        }
    }

    public void warn(String s, Throwable throwable) {
        if(tracer.isWarningEnabled()){
            tracer.warning(sessionId + s, throwable);
        }
    }


    // Error (Severe) trace methods
    public boolean isErrorEnabled() {
        return tracer.isSevereEnabled();
    }

    public void error(String s) {
        tracer.severe(sessionId + s);
    }

    public void error(String s, Object o) {
        if(tracer.isWarningEnabled()){
            String formattedMessage = MessageFormatter.format(sessionId + s, o);
            tracer.severe(formattedMessage);
        }
    }

    public void error(String s, Object o, Object o1) {
        if(tracer.isWarningEnabled()){
            String formattedMessage = MessageFormatter.format(sessionId + s, o, o1);
            tracer.severe(formattedMessage);
        }
    }

    public void error(String s, Object... objects) {
        if(tracer.isWarningEnabled()){
            String formattedMessage = MessageFormatter.arrayFormat(sessionId + s, objects);
            tracer.severe(formattedMessage);
        }
    }

    public void error(String s, Throwable throwable) {
        if(tracer.isWarningEnabled()){
            tracer.severe(sessionId + s, throwable);
        }
    }



    /////////////////// trace methods with Marker - not implemented ////////////////////////////////////////
    public boolean isDebugEnabled(Marker marker) {
        return false;
    }

    public void debug(Marker marker, String s) {
        // marker method not required
    }

    public void debug(Marker marker, String s, Object o) {
        // marker method not required
    }

    public void debug(Marker marker, String s, Object o, Object o1) {
        // marker method not required
    }

    public void debug(Marker marker, String s, Object... objects) {
        // marker method not required
    }

    public void debug(Marker marker, String s, Throwable throwable) {
        // marker method not required
    }


    public boolean isInfoEnabled(Marker marker) {
        return false;
    }

    public void info(Marker marker, String s) {
        // marker method not required
    }

    public void info(Marker marker, String s, Object o) {
        // marker method not required
    }

    public void info(Marker marker, String s, Object o, Object o1) {
        // marker method not required
    }

    public void info(Marker marker, String s, Object... objects) {
        // marker method not required
    }

    public void info(Marker marker, String s, Throwable throwable) {
        // marker method not required
    }

    public boolean isWarnEnabled(Marker marker) {
        return false;
    }

    public void warn(Marker marker, String s) {
        // marker method not required
    }

    public void warn(Marker marker, String s, Object o) {
        // marker method not required
    }

    public void warn(Marker marker, String s, Object o, Object o1) {
        // marker method not required
    }

    public void warn(Marker marker, String s, Object... objects) {
        // marker method not required
    }

    public void warn(Marker marker, String s, Throwable throwable) {
        // marker method not required
    }


    public boolean isErrorEnabled(Marker marker) {
        return false;
    }

    public void error(Marker marker, String s) {
        // marker method not required
    }

    public void error(Marker marker, String s, Object o) {
        // marker method not required
    }

    public void error(Marker marker, String s, Object o, Object o1) {
        // marker method not required
    }

    public void error(Marker marker, String s, Object... objects) {
        // marker method not required
    }

    public void error(Marker marker, String s, Throwable throwable) {
        // marker method not required
    }

    public boolean isTraceEnabled(Marker marker) {
        return false;
    }

    public void trace(Marker marker, String s) {
        // marker method not required
    }

    public void trace(Marker marker, String s, Object o) {
        // marker method not required
    }

    public void trace(Marker marker, String s, Object o, Object o1) {
        // marker method not required
    }

    public void trace(Marker marker, String s, Object... objects) {
        // marker method not required
    }

    public void trace(Marker marker, String s, Throwable throwable) {
        // marker method not required
    }
}
