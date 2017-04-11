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
package pl.ovoo.slee.resource.sip.broker.service;

import pl.ovoo.slee.resource.sip.broker.service.eventqueue.SessionEventHandler;

/**
 * This is the wrapper for a dialog to fetch corresponding session (and dialog handler data).
 */
public class HandlerReferenceWrapper {

    private final SessionEventHandler sessionHandler;
    private final B2BDialogsHandler dialogHandler;
    private B2BDialogsHandler specialInfoSender;

    public HandlerReferenceWrapper(SessionEventHandler sessionHandler, B2BDialogsHandler dialogHandler){
        this.sessionHandler = sessionHandler;
        this.dialogHandler = dialogHandler;
    }

    public HandlerReferenceWrapper(SessionEventHandler sessionHandler){
        this(sessionHandler, null);
    }

    public SessionEventHandler getSessionHandler(){
        return sessionHandler;
    }

    public B2BDialogsHandler getDialogHandler(){
        return dialogHandler;
    }

    public void setSpecialInfoSender(B2BDialogsHandler infoSender){
        specialInfoSender = infoSender;
    }

    public B2BDialogsHandler getSpecialInfoSender(){
        return specialInfoSender;
    }
}
