package com.craftersy.treadmill.client

import kotlinx.coroutines.channels.ReceiveChannel

/** Moves raw frames between the client and the treadmill. */
public interface Transport {
    /** Sends one command frame. */
    public suspend fun write(frame: ByteArray)

    /**
     * Status notifications as received. The channel is closed when the
     * connection ends, including after [close]. Only one consumer may read it.
     */
    public val frames: ReceiveChannel<ByteArray>

    /** Ends the connection. It does not wait; [frames] closes once it is done. */
    public fun close()
}

/** Base class of the errors a [Client] reports. */
public open class TreadmillException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The treadmill has not sent a status frame yet. */
public class NoStatusException : TreadmillException("treadmill: no status received yet")

/** Speed and incline can only be changed while the belt runs. */
public class NotRunningException(public val state: com.craftersy.treadmill.protocol.State) :
    TreadmillException("treadmill: belt is not running (state $state)")

/** No status frame confirmed the command in time. */
public class NotAcknowledgedException : TreadmillException("treadmill: command not acknowledged")

/** The connection has ended. */
public class ClosedException : TreadmillException("treadmill: connection closed")

/** Thrown by [ReconnectingTransport.write] while the connection is down. */
public class DisconnectedException : TreadmillException("treadmill: disconnected, reconnecting")
