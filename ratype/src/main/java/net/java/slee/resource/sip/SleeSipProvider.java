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
package net.java.slee.resource.sip;


import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.SipProvider;
import javax.sip.TransportNotSupportedException;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.HeaderFactory;
import javax.sip.header.ViaHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Response;


public interface SleeSipProvider extends SipProvider {
    public AddressFactory getAddressFactory();

    public HeaderFactory getHeaderFactory();

    public MessageFactory getMessageFactory();

    public DialogActivity getNewDialog(Address from, Address to) throws SipException;

    public DialogActivity getNewDialog(DialogActivity incomingDialog, boolean useSameCallId) throws SipException;

    public boolean isLocalSipURI(SipURI uri);

    public boolean isLocalHostname(String host);

    public SipURI getLocalSipURI(String transport);

    public ViaHeader getLocalVia(String transport, String branch) throws TransportNotSupportedException;

    public DialogActivity forwardForkedResponse(ServerTransaction origServerTransaction, Response response) throws
            SipException;

    public boolean acceptCancel(CancelRequestEvent cancelEvent, boolean isProxy);
}
