package dev.kauzes.mizan.merchant.domain

/**
 * What the platform said when asked whether it is up.
 *
 * Three answers rather than a yes or no, because the two ways of not being up are different problems
 * for whoever is holding the phone. A platform that answered and is not healthy is somebody else's
 * outage. A platform that could not be reached at all is, far more often, this phone: no network, the
 * wrong address, or plain HTTP refused by the device.
 */
sealed interface PlatformHealth {

    /** The gateway answered, and says the platform is up. */
    data object Up : PlatformHealth

    /** The gateway answered, and the answer was not that it is up. */
    data class Down(val because: String) : PlatformHealth

    /** Nothing answered. */
    data class Unreachable(val because: String) : PlatformHealth
}
