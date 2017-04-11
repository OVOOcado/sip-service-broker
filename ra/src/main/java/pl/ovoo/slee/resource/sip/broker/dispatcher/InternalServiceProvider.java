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
package pl.ovoo.slee.resource.sip.broker.dispatcher;

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.Transaction;
import javax.sip.TransactionAlreadyExistsException;
import javax.sip.TransactionUnavailableException;
import javax.sip.header.CallIdHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;

/**
 * This is the ServiceProvider implementation that connects to SBBs (internal services).
 * It should fulfill the actual SIP ratype contract (activities management, distribution of events, etc).
 */
public class InternalServiceProvider implements ServiceProvider {

    private static final String NOT_SUPPORTED = "Internal Services not supported yet";

    public ClientTransaction getNewClientTransaction(Request request) throws TransactionUnavailableException {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    public Dialog getNewDialog(Transaction transaction) throws SipException {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    public ServerTransaction getNewServerTransaction(Request request) throws TransactionAlreadyExistsException, TransactionUnavailableException {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    public void sendRequest(Request request) throws SipException {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    public void sendResponse(Response response) throws SipException {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    public CallIdHeader getNewCallId() {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }
}
