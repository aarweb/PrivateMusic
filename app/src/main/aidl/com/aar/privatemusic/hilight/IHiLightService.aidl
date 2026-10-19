package com.aar.privatemusic.hilight;

// Fixed-purpose bridge: no shell commands, arbitrary colours or file access.
interface IHiLightService {
    boolean isSupported() = 0;
    void pulse(IBinder owner) = 1;
    void stop() = 2;
    void destroy() = 16777114;
}
