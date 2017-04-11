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
package pl.ovoo.slee.resource.sip.broker;

/**
 * This is the Usage Parameters interface for the SIP Broker.
 */
public interface SipBrokerUsageParameters {

    // Orchestrated session stats
    void incrementRunningOrchestratedSessionsCount(long i);
    long getRunningOrchestratedSessionsCount();

    void incrementOrchestratedSessionsCount(long i);
    long getOrchestratedSessionsCount();

    void incrementSuccessfulSessionsCount(long i);
    long getSuccessfulSessionsCount();

    void incrementAbortedSessionsCount(long i);
    long getAbortedSessionsCount();

    // Ping session stats
    void incrementPingSessionsStarted(long i);
    long getPingSessionsStarted();

    void incrementPingSessionsSuccessCount(long i);
    long getPingSessionsSuccessCount();

    void incrementPingSessionsErrorCount(long i);
    long getPingSessionsErrorCount();

    // Auxiliary session stats
    void incrementRunningAuxSessionsCount(long i);
    long getRunningAuxSessionsCount();

    void incrementAuxSessionsCount(long i);
    long getAuxSessionsCount();

    void incrementSuccessfulAuxSessionsCount(long i);
    long getCompletedAuxSessionsCount();

    void incrementAbortedAuxSessionsCount(long i);
    long getAbortedAuxSessionsCount();
}
