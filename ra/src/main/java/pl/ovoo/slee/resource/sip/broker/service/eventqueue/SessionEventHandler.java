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
package pl.ovoo.slee.resource.sip.broker.service.eventqueue;

import org.slf4j.Logger;
import pl.ovoo.slee.resource.sip.broker.service.HandlerReferenceWrapper;

import java.util.EventObject;

/**
 * This is an abstract session event handler. Should be extended by both orchestrated session and ping session.
 * Its purpose is to put particular session event on the event queue for processing.
 */
public abstract class SessionEventHandler {
    protected Logger logger;
    protected final HandlerReferenceWrapper itsReferenceWrapper = new HandlerReferenceWrapper(this);
    private SessionTasks itsSessionTasks;

    protected void initSessionTasks(){
        itsSessionTasks = new SessionTasksImpl(this);
    }

    public abstract void handleNextEvent(EventObject event);

    public abstract String getID();

    public SessionTasks getItsSessionTasks() {
        return itsSessionTasks;
    }

    /**
     * Returns SipBrokerLogger instance.
     * Use this within session context in order to keep session traceable.
     */
    public abstract Logger getSessionLogger(Class clazz);
}
