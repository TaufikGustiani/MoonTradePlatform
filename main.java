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

    static boolean isValidSymbol(String s) {
        return s != null && SYMBOL_PATTERN.matcher(s).matches();
    }

    static boolean isValidAddress(String s) {
        return s != null && ADDR_PATTERN.matcher(s).matches();
    }
}

// -----------------------------------------------------------------------------
// LUNAR TICK SIZE
// -----------------------------------------------------------------------------

final class LunarTickSize {
    private static final Map<Integer, BigDecimal> TICK_MAP = new HashMap<>();
    static {
        TICK_MAP.put(0, new BigDecimal("0.0001"));
        TICK_MAP.put(1, new BigDecimal("0.001"));
        TICK_MAP.put(2, new BigDecimal("0.01"));
        TICK_MAP.put(3, new BigDecimal("0.1"));
        TICK_MAP.put(4, new BigDecimal("1"));
        TICK_MAP.put(5, new BigDecimal("10"));
        TICK_MAP.put(6, new BigDecimal("100"));
    }

    static BigDecimal tickForDecimals(int decimals) {
        return TICK_MAP.getOrDefault(Math.min(decimals, 6), new BigDecimal("0.01"));
    }

    static BigDecimal roundToTick(BigDecimal price, BigDecimal tick) {
        if (tick.signum() <= 0) return price;
        return price.divide(tick, 0, RoundingMode.HALF_UP).multiply(tick);
    }
}

// -----------------------------------------------------------------------------
// ORDER SIDE & TYPE
// -----------------------------------------------------------------------------

enum LunarSide { BUY, SELL }

enum LunarOrderType { LIMIT, MARKET, FOK, IOC }

// -----------------------------------------------------------------------------
// ORDER RECORD
// -----------------------------------------------------------------------------

final class LunarOrder {
    private final String orderId;
    private final String marketId;
    private final String traderAddress;
    private final LunarSide side;
    private final LunarOrderType type;
    private final BigDecimal price;
    private final BigInteger sizeWei;
    private final BigInteger filledWei;
    private final long createdAt;
    private final long expiryBlock;
    private volatile boolean cancelled;

    LunarOrder(String orderId, String marketId, String traderAddress, LunarSide side, LunarOrderType type,
               BigDecimal price, BigInteger sizeWei, long expiryBlock) {
        this.orderId = orderId;
        this.marketId = marketId;
        this.traderAddress = traderAddress;
        this.side = side;
        this.type = type;
        this.price = price;
        this.sizeWei = sizeWei;
        this.filledWei = BigInteger.ZERO;
        this.createdAt = System.currentTimeMillis();
        this.expiryBlock = expiryBlock;
        this.cancelled = false;
    }

    String getOrderId() { return orderId; }
    String getMarketId() { return marketId; }
    String getTraderAddress() { return traderAddress; }
    LunarSide getSide() { return side; }
    LunarOrderType getType() { return type; }
    BigDecimal getPrice() { return price; }
    BigInteger getSizeWei() { return sizeWei; }
    BigInteger getFilledWei() { return filledWei; }
    long getCreatedAt() { return createdAt; }
    long getExpiryBlock() { return expiryBlock; }
    boolean isCancelled() { return cancelled; }
    void setCancelled(boolean c) { cancelled = c; }
    BigInteger getRemainingWei() { return sizeWei.subtract(filledWei); }
}

// -----------------------------------------------------------------------------
// TRADE RECORD
// -----------------------------------------------------------------------------

final class LunarTrade {
    private final String tradeId;
    private final String marketId;
    private final String buyOrderId;
    private final String sellOrderId;
    private final String buyerAddress;
    private final String sellerAddress;
    private final BigDecimal price;
    private final BigInteger qtyWei;
    private final long executedAt;

    LunarTrade(String tradeId, String marketId, String buyOrderId, String sellOrderId,
               String buyerAddress, String sellerAddress, BigDecimal price, BigInteger qtyWei) {
        this.tradeId = tradeId;
        this.marketId = marketId;
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.buyerAddress = buyerAddress;
        this.sellerAddress = sellerAddress;
        this.price = price;
        this.qtyWei = qtyWei;
        this.executedAt = System.currentTimeMillis();
    }

    String getTradeId() { return tradeId; }
    String getMarketId() { return marketId; }
    String getBuyOrderId() { return buyOrderId; }
    String getSellOrderId() { return sellOrderId; }
    String getBuyerAddress() { return buyerAddress; }
    String getSellerAddress() { return sellerAddress; }
    BigDecimal getPrice() { return price; }
    BigInteger getQtyWei() { return qtyWei; }
    long getExecutedAt() { return executedAt; }
}

// -----------------------------------------------------------------------------
// MARKET DEFINITION
// -----------------------------------------------------------------------------

final class LunarMarket {
    private final String marketId;
    private final String baseSymbol;
    private final String quoteSymbol;
    private final int baseDecimals;
    private final int quoteDecimals;
    private final BigDecimal tickSize;
    private final BigInteger minOrderWei;
    private final BigInteger maxOrderWei;
    private final long createdAt;

    LunarMarket(String marketId, String baseSymbol, String quoteSymbol, int baseDecimals, int quoteDecimals,
                BigDecimal tickSize, BigInteger minOrderWei, BigInteger maxOrderWei) {
        this.marketId = marketId;
        this.baseSymbol = baseSymbol;
        this.quoteSymbol = quoteSymbol;
        this.baseDecimals = baseDecimals;
        this.quoteDecimals = quoteDecimals;
        this.tickSize = tickSize;
        this.minOrderWei = minOrderWei;
        this.maxOrderWei = maxOrderWei;
        this.createdAt = System.currentTimeMillis();
    }

    String getMarketId() { return marketId; }
    String getBaseSymbol() { return baseSymbol; }
    String getQuoteSymbol() { return quoteSymbol; }
    int getBaseDecimals() { return baseDecimals; }
    int getQuoteDecimals() { return quoteDecimals; }
    BigDecimal getTickSize() { return tickSize; }
    BigInteger getMinOrderWei() { return minOrderWei; }
    BigInteger getMaxOrderWei() { return maxOrderWei; }
    long getCreatedAt() { return createdAt; }
}

// -----------------------------------------------------------------------------
// BALANCE LEDGER
// -----------------------------------------------------------------------------

final class LunarBalanceLedger {
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, BigInteger>> balances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BigInteger> locked = new ConcurrentHashMap<>();

    BigInteger balanceOf(String address, String symbol) {
        ConcurrentHashMap<String, BigInteger> bySymbol = balances.get(address);
        if (bySymbol == null) return BigInteger.ZERO;
        return bySymbol.getOrDefault(symbol, BigInteger.ZERO);
    }

    BigInteger lockedOf(String address, String symbol) {
        return locked.getOrDefault(address + ":" + symbol, BigInteger.ZERO);
    }

    void credit(String address, String symbol, BigInteger amount) {
        balances.computeIfAbsent(address, k -> new ConcurrentHashMap<>())
                .merge(symbol, amount == null ? BigInteger.ZERO : amount, LunarWeiMath::addSafe);
    }

    void debit(String address, String symbol, BigInteger amount) {
        if (amount == null || amount.signum() <= 0) return;
        ConcurrentHashMap<String, BigInteger> bySymbol = balances.get(address);
        if (bySymbol == null) throw new LunarTradeException(LunarErrorCodes.LUNAR_INSUFFICIENT_BAL, "No balance");
        bySymbol.merge(symbol, amount, (a, b) -> {
            BigInteger r = a.subtract(b);
            if (r.signum() < 0) throw new LunarTradeException(LunarErrorCodes.LUNAR_INSUFFICIENT_BAL, "Insufficient");
            return r;
        });
    }

    void lock(String address, String symbol, BigInteger amount) {
        if (amount == null || amount.signum() <= 0) return;
        debit(address, symbol, amount);
        locked.merge(address + ":" + symbol, amount, LunarWeiMath::addSafe);
    }

    void unlock(String address, String symbol, BigInteger amount) {
        if (amount == null || amount.signum() <= 0) return;
        String key = address + ":" + symbol;
        BigInteger cur = locked.get(key);
        if (cur == null || cur.compareTo(amount) < 0)
            throw new LunarTradeException(LunarErrorCodes.LUNAR_INTEGRITY, "Lock underflow");
        locked.put(key, cur.subtract(amount));
        credit(address, symbol, amount);
    }
}

// -----------------------------------------------------------------------------
// ORDER BOOK (price-time priority)
// -----------------------------------------------------------------------------

final class LunarOrderBook {
    private final String marketId;
    private final TreeMap<BigDecimal, List<LunarOrder>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<BigDecimal, List<LunarOrder>> asks = new TreeMap<>();
    private final Map<String, LunarOrder> orderById = new ConcurrentHashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    LunarOrderBook(String marketId) {
        this.marketId = marketId;
    }

    String getMarketId() { return marketId; }

    void addOrder(LunarOrder order) {
        lock.writeLock().lock();
        try {
            orderById.put(order.getOrderId(), order);
            TreeMap<BigDecimal, List<LunarOrder>> book = order.getSide() == LunarSide.BUY ? bids : asks;
            book.computeIfAbsent(order.getPrice(), k -> new ArrayList<>()).add(order);
        } finally {
            lock.writeLock().unlock();
        }
    }

    void removeOrder(String orderId) {
        lock.writeLock().lock();
        try {
            LunarOrder o = orderById.remove(orderId);
            if (o == null) return;
            TreeMap<BigDecimal, List<LunarOrder>> book = o.getSide() == LunarSide.BUY ? bids : asks;
            List<LunarOrder> list = book.get(o.getPrice());
            if (list != null) {
                list.removeIf(ord -> ord.getOrderId().equals(orderId));
                if (list.isEmpty()) book.remove(o.getPrice());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    LunarOrder getOrder(String orderId) {
        lock.readLock().lock();
        try {
            return orderById.get(orderId);
        } finally {
            lock.readLock().unlock();
        }
    }

    List<LunarOrder> getBids(int depth) {
        lock.readLock().lock();
        try {
            return bids.values().stream().flatMap(List::stream).limit(depth).collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    List<LunarOrder> getAsks(int depth) {
        lock.readLock().lock();
        try {
            return asks.values().stream().flatMap(List::stream).limit(depth).collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    Optional<BigDecimal> bestBid() {
        lock.readLock().lock();
        try {
            return bids.isEmpty() ? Optional.empty() : Optional.of(bids.firstKey());
        } finally {
            lock.readLock().unlock();
        }
    }

    Optional<BigDecimal> bestAsk() {
        lock.readLock().lock();
        try {
            return asks.isEmpty() ? Optional.empty() : Optional.of(asks.firstKey());
        } finally {
            lock.readLock().unlock();
        }
    }
}

// -----------------------------------------------------------------------------
// EVENT LOG (in-memory)
// -----------------------------------------------------------------------------

final class LunarEventLog {
    private static final int MAX_EVENTS = 50_000;
    private final List<LunarEvent> events = new CopyOnWriteArrayList<>();

    static final class LunarEvent {
        final long time;
        final String kind;
        final String payload;

        LunarEvent(long time, String kind, String payload) {
            this.time = time;
            this.kind = kind;
            this.payload = payload;
        }
    }

    void emit(String kind, String payload) {
        events.add(new LunarEvent(System.currentTimeMillis(), kind, payload));
        while (events.size() > MAX_EVENTS) events.remove(0);
    }

    List<LunarEvent> recent(int n) {
        int size = events.size();
        if (n >= size) return new ArrayList<>(events);
        return new ArrayList<>(events.subList(size - n, size));
    }
}

// -----------------------------------------------------------------------------
// FEE CONFIG
// -----------------------------------------------------------------------------

final class LunarFeeConfig {
    private final int makerBps;
    private final int takerBps;
    private final BigInteger feeCapWei;

    LunarFeeConfig(int makerBps, int takerBps, BigInteger feeCapWei) {
        this.makerBps = makerBps;
        this.takerBps = takerBps;
        this.feeCapWei = feeCapWei;
    }

    int getMakerBps() { return makerBps; }
    int getTakerBps() { return takerBps; }
    BigInteger getFeeCapWei() { return feeCapWei; }
}

// -----------------------------------------------------------------------------
// ID GENERATORS
// -----------------------------------------------------------------------------

final class LunarIdGen {
    private static final AtomicLong orderSeq = new AtomicLong(17000 + new Random().nextInt(8000));
    private static final AtomicLong tradeSeq = new AtomicLong(29000 + new Random().nextInt(9000));
    private static final AtomicLong marketSeq = new AtomicLong(400 + new Random().nextInt(200));

    static String nextOrderId() {
        return "LUNAR-O-" + orderSeq.incrementAndGet() + "-" + Long.toHexString(System.nanoTime());
    }

    static String nextTradeId() {
        return "LUNAR-T-" + tradeSeq.incrementAndGet() + "-" + Long.toHexString(System.nanoTime());
    }

    static String nextMarketId() {
        return "LUNAR-M-" + marketSeq.incrementAndGet();
    }
}

// -----------------------------------------------------------------------------
// MATCHING ENGINE
// -----------------------------------------------------------------------------

final class LunarMatchingEngine {
    private final LunarOrderBook book;
    private final LunarBalanceLedger ledger;
    private final LunarFeeConfig feeConfig;
    private final LunarEventLog eventLog;
    private final String baseSymbol;
    private final String quoteSymbol;
    private final List<LunarTrade> lastTrades = new CopyOnWriteArrayList<>();
    private java.util.function.Consumer<LunarTrade> onTradeCallback;

    LunarMatchingEngine(LunarOrderBook book, LunarBalanceLedger ledger, LunarFeeConfig feeConfig,
                        LunarEventLog eventLog, String baseSymbol, String quoteSymbol) {
        this.book = book;
        this.ledger = ledger;
        this.feeConfig = feeConfig;
        this.eventLog = eventLog;
        this.baseSymbol = baseSymbol;
        this.quoteSymbol = quoteSymbol;
    }

    void setOnTradeCallback(java.util.function.Consumer<LunarTrade> onTradeCallback) {
        this.onTradeCallback = onTradeCallback;
    }

    List<LunarTrade> getLastTrades() { return new ArrayList<>(lastTrades); }

    void match(LunarOrder incoming) {
        if (incoming.getSide() == LunarSide.BUY) {
            matchBuy(incoming);
        } else {
            matchSell(incoming);
        }
    }

    private void matchBuy(LunarOrder buyOrder) {
        BigInteger remaining = buyOrder.getRemainingWei();
        if (remaining.signum() <= 0) return;
        Optional<BigDecimal> bestAsk = book.bestAsk();
        if (bestAsk.isEmpty() || buyOrder.getPrice().compareTo(bestAsk.get()) < 0) return;
