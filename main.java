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
            case LUNAR_ORDER_FILLED: return "Order already filled";
            case LUNAR_ORDER_CANCELLED: return "Order cancelled";
            case LUNAR_PLATFORM_HALTED: return "Platform halted";
            case LUNAR_XFER_FAIL: return "Transfer failed";
            case LUNAR_INSUFFICIENT_BAL: return "Insufficient balance";
            case LUNAR_CAP_EXCEEDED: return "Order or position cap exceeded";
            case LUNAR_BAD_TICK: return "Price not on tick";
            case LUNAR_SLIPPAGE: return "Slippage exceeded";
            case LUNAR_INTEGRITY: return "Integrity check failed";
            case LUNAR_BAD_SYMBOL: return "Invalid symbol";
            default: return "Unknown: " + code;
        }
    }

    static List<String> allCodes() {
        return List.of(LUNAR_ZERO_AMOUNT, LUNAR_ZERO_ADDRESS, LUNAR_NOT_OPERATOR, LUNAR_MARKET_MISSING,
            LUNAR_MARKET_EXISTS, LUNAR_ORDER_MISSING, LUNAR_ORDER_FILLED, LUNAR_ORDER_CANCELLED,
            LUNAR_PLATFORM_HALTED, LUNAR_XFER_FAIL, LUNAR_INSUFFICIENT_BAL, LUNAR_CAP_EXCEEDED,
            LUNAR_BAD_TICK, LUNAR_SLIPPAGE, LUNAR_INTEGRITY, LUNAR_BAD_SYMBOL);
    }
}

// -----------------------------------------------------------------------------
// WEI / U256 SAFE MATH
// -----------------------------------------------------------------------------

final class LunarWeiMath {
    private static final BigInteger MAX_U256 = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);

    static BigInteger clampU256(BigInteger value) {
        if (value == null || value.signum() < 0) return BigInteger.ZERO;
        if (value.compareTo(MAX_U256) > 0) return MAX_U256;
        return value;
    }

    static BigInteger addSafe(BigInteger a, BigInteger b) {
        BigInteger sum = (a == null ? BigInteger.ZERO : a).add(b == null ? BigInteger.ZERO : b);
        return clampU256(sum);
    }

    static BigInteger subSafe(BigInteger a, BigInteger b) {
        BigInteger aa = a == null ? BigInteger.ZERO : a;
        BigInteger bb = b == null ? BigInteger.ZERO : b;
        if (bb.compareTo(aa) > 0) return BigInteger.ZERO;
        return aa.subtract(bb);
    }

    static BigInteger mulSafe(BigInteger a, BigInteger b) {
        if (a == null || b == null) return BigInteger.ZERO;
        BigInteger p = a.multiply(b);
        return clampU256(p);
    }
}

// -----------------------------------------------------------------------------
// SYMBOL & ADDRESS VALIDATION
// -----------------------------------------------------------------------------

final class LunarSymbolValidator {
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("^[A-Za-z0-9_]{2,24}$");
    private static final Pattern ADDR_PATTERN = Pattern.compile("^0x[0-9a-fA-F]{40}$");
