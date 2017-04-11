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

import javax.sip.Dialog;
import javax.sip.RequestEvent;
import javax.sip.ServerTransaction;
import javax.sip.message.Request;


public class CancelRequestEvent extends RequestEvent {

    private static final long serialVersionUID = 1L;

    private final ServerTransaction itsMatchingInviteTransaction;

    public CancelRequestEvent(Object source, ServerTransaction serverTransaction, ServerTransaction
            matchingTransaction, Dialog dialog, Request request) {
        super(source, serverTransaction, dialog, request);
        itsMatchingInviteTransaction = matchingTransaction;
    }

    public ServerTransaction getMatchingTransaction() {
        return itsMatchingInviteTransaction;
    }

    @Override
    public String toString() {
        return "CancelRequestEvent[ cancelST = " + getServerTransaction() + ", inviteST = " +
                itsMatchingInviteTransaction + ", inviteDialog = " + getDialog() + " ]";
    }
}
