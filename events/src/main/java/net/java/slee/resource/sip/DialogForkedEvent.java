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

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.ResponseEvent;
import javax.sip.message.Response;


public class DialogForkedEvent extends ResponseEvent {

    private static final long serialVersionUID = 1L;

    private final Dialog itsForkedDialog;

    public DialogForkedEvent(Object source, ClientTransaction clientTransaction, Dialog originalDialog, Dialog
            forkedDialog, Response response) {
        super(source, clientTransaction, originalDialog, response);
        itsForkedDialog = forkedDialog;
    }

    public Dialog getItsForkedDialog() {
        return itsForkedDialog;
    }

}
