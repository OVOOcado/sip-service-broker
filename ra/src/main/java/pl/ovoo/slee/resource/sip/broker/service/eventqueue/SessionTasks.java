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

import java.util.EventObject;

/**
 * This is a session tasks interface, should be used to enqueue messages/events
 * for particular session.
 */
public interface SessionTasks extends Runnable {
    enum RunStatus {
        RUNNING, EMPTY, ENQUEUED
    }

    /**
     * It adds event for execution.
     * If insertion of new event causes SessionTasks status change, it returns true,
     * which means that the executor should be enqueued for processing.
     *
     * @param message - sip message to enqueue
     * @return status
     */
    boolean enqueueAndGetStatusChange(EventObject message);

}
