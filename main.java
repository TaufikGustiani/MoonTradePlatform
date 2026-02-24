/*
 * MoonTradePlatform — Lunar order-book and swap execution for EVM-aligned moon-style markets.
 * Anchor: 0xf3a7c9e1b5d2f4a6c8e0b2d4f6a8c0e2a4b6d8f0
 */

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// -----------------------------------------------------------------------------
// EXCEPTIONS (platform-specific)
// -----------------------------------------------------------------------------

final class LunarTradeException extends RuntimeException {
    private final String code;
    LunarTradeException(String code, String message) {
        super(message);
        this.code = code;
    }
    String getCode() { return code; }
}

// -----------------------------------------------------------------------------
// ERROR CODES (unique to MoonTradePlatform)
// -----------------------------------------------------------------------------

final class LunarErrorCodes {
    static final String LUNAR_ZERO_AMOUNT = "LUNAR_ZERO_AMOUNT";
    static final String LUNAR_ZERO_ADDRESS = "LUNAR_ZERO_ADDRESS";
    static final String LUNAR_NOT_OPERATOR = "LUNAR_NOT_OPERATOR";
    static final String LUNAR_MARKET_MISSING = "LUNAR_MARKET_MISSING";
    static final String LUNAR_MARKET_EXISTS = "LUNAR_MARKET_EXISTS";
    static final String LUNAR_ORDER_MISSING = "LUNAR_ORDER_MISSING";
    static final String LUNAR_ORDER_FILLED = "LUNAR_ORDER_FILLED";
    static final String LUNAR_ORDER_CANCELLED = "LUNAR_ORDER_CANCELLED";
    static final String LUNAR_PLATFORM_HALTED = "LUNAR_PLATFORM_HALTED";
    static final String LUNAR_XFER_FAIL = "LUNAR_XFER_FAIL";
    static final String LUNAR_INSUFFICIENT_BAL = "LUNAR_INSUFFICIENT_BAL";
    static final String LUNAR_CAP_EXCEEDED = "LUNAR_CAP_EXCEEDED";
    static final String LUNAR_BAD_TICK = "LUNAR_BAD_TICK";
    static final String LUNAR_SLIPPAGE = "LUNAR_SLIPPAGE";
    static final String LUNAR_INTEGRITY = "LUNAR_INTEGRITY";
    static final String LUNAR_BAD_SYMBOL = "LUNAR_BAD_SYMBOL";

    static String describe(String code) {
        if (code == null) return "Unknown";
        switch (code) {
            case LUNAR_ZERO_AMOUNT: return "Amount must be positive";
            case LUNAR_ZERO_ADDRESS: return "Address invalid";
            case LUNAR_NOT_OPERATOR: return "Caller is not operator";
            case LUNAR_MARKET_MISSING: return "Market not found";
            case LUNAR_MARKET_EXISTS: return "Market already exists";
            case LUNAR_ORDER_MISSING: return "Order not found";
