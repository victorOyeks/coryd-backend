package org.coryd.trip.entity;

/**
 * Whose fault a cancellation was - drives the refund when ops cancels: only RIDER can cost the rider the
 * late-cancellation fee under CancellationPolicy; DRIVER and PLATFORM always refund the rider in full.
 */
public enum CancellationFault {
    DRIVER,
    RIDER,
    PLATFORM
}
