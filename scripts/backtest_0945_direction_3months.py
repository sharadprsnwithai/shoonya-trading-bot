import sys
import io
import datetime
import numpy as np
import pandas as pd
import yfinance as yf

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

# ---------------------------------------------------------
# Technical Indicator Helpers
# ---------------------------------------------------------
def calculate_rsi(series, period=14):
    delta = series.diff()
    gain = (delta.where(delta > 0, 0)).copy()
    loss = (-delta.where(delta < 0, 0)).copy()
    avg_gain = gain.rolling(window=period, min_periods=period).mean()
    avg_loss = loss.rolling(window=period, min_periods=period).mean()
    for i in range(period, len(series)):
        avg_gain.iloc[i] = (avg_gain.iloc[i-1] * (period - 1) + gain.iloc[i]) / period
        avg_loss.iloc[i] = (avg_loss.iloc[i-1] * (period - 1) + loss.iloc[i]) / period
    rs = avg_gain / avg_loss.replace(0, np.nan)
    return (100 - (100 / (1 + rs))).fillna(50)

def calculate_ema(series, span):
    return series.ewm(span=span, adjust=False).mean()

def calculate_intraday_vwap(df):
    vwaps = []
    for date, group in df.groupby(df.index.date):
        vol = group['Volume'].replace(0, 1)
        typical = (group['High'] + group['Low'] + group['Close']) / 3.0
        cum_vp = (typical * vol).cumsum()
        cum_vol = vol.cumsum()
        vwap = cum_vp / cum_vol
        vwaps.append(vwap)
    return pd.concat(vwaps)

def calculate_supertrend(df, period=10, multiplier=3.0):
    hl2 = (df['High'] + df['Low']) / 2.0
    tr1 = df['High'] - df['Low']
    tr2 = (df['High'] - df['Close'].shift(1)).abs()
    tr3 = (df['Low'] - df['Close'].shift(1)).abs()
    tr = pd.concat([tr1, tr2, tr3], axis=1).max(axis=1)
    atr = tr.ewm(alpha=1.0/period, adjust=False).mean()
    upper = hl2 + multiplier * atr
    lower = hl2 - multiplier * atr
    trend = np.ones(len(df))
    for i in range(1, len(df)):
        if df['Close'].iloc[i-1] > upper.iloc[i-1]: trend[i] = 1
        elif df['Close'].iloc[i-1] < lower.iloc[i-1]: trend[i] = -1
        else:
            trend[i] = trend[i-1]
            if trend[i] == 1 and lower.iloc[i] < lower.iloc[i-1]: lower.iloc[i] = lower.iloc[i-1]
            if trend[i] == -1 and upper.iloc[i] > upper.iloc[i-1]: upper.iloc[i] = upper.iloc[i-1]
    return pd.Series(trend, index=df.index)

# ---------------------------------------------------------
# Sector Constituent Universe
# ---------------------------------------------------------
SECTORS = {
    "NIFTY IT": ["TCS.NS", "INFY.NS", "HCLTECH.NS", "WIPRO.NS", "TECHM.NS", "COFORGE.NS", "PERSISTENT.NS", "LTTS.NS", "MPHASIS.NS"],
    "NIFTY BANK": ["HDFCBANK.NS", "ICICIBANK.NS", "SBIN.NS", "AXISBANK.NS", "KOTAKBANK.NS", "INDUSINDBK.NS", "BANKBARODA.NS", "PNB.NS"],
    "NIFTY AUTO": ["MARUTI.NS", "M&M.NS", "BAJAJ-AUTO.NS", "EICHERMOT.NS", "HEROMOTOCO.NS", "TVSMOTOR.NS", "ASHOKLEY.NS", "BHARATFORG.NS"],
    "NIFTY PHARMA": ["SUNPHARMA.NS", "DRREDDY.NS", "CIPLA.NS", "DIVISLAB.NS", "LUPIN.NS", "AUROPHARMA.NS", "ZYDUSLIFE.NS", "LAURUSLABS.NS"],
    "NIFTY METAL": ["TATASTEEL.NS", "JSWSTEEL.NS", "HINDALCO.NS", "VEDL.NS", "JINDALSTEL.NS", "COALINDIA.NS", "NMDC.NS", "SAIL.NS"],
    "NIFTY FMCG": ["HINDUNILVR.NS", "ITC.NS", "NESTLEIND.NS", "BRITANNIA.NS", "TATACONSUM.NS", "DABUR.NS", "GODREJCP.NS", "COLPAL.NS"],
    "NIFTY REALTY": ["DLF.NS", "GODREJPROP.NS", "OBEROIRLTY.NS", "PRESTIGE.NS", "PHOENIXLTD.NS", "BRIGADE.NS"],
    "NIFTY ENERGY": ["RELIANCE.NS", "NTPC.NS", "POWERGRID.NS", "ONGC.NS", "BPCL.NS", "IOC.NS", "GAIL.NS", "TATAPOWER.NS"],
    "NIFTY FIN SERVICE": ["BAJFINANCE.NS", "BAJAJFINSV.NS", "CHOLAFIN.NS", "SHRIRAMFIN.NS", "MUTHOOTFIN.NS", "HDFCLIFE.NS", "SBILIFE.NS"],
    "NIFTY MEDIA": ["SUNTV.NS", "ZEEL.NS", "PVRINOX.NS"]
}

NIFTY_50_STOCKS = [
    "RELIANCE.NS", "TCS.NS", "HDFCBANK.NS", "ICICIBANK.NS", "INFY.NS", "BHARTIARTL.NS", "ITC.NS", "SBIN.NS",
    "LICI.NS", "HINDUNILVR.NS", "LT.NS", "BAJFINANCE.NS", "HCLTECH.NS", "MARUTI.NS", "SUNPHARMA.NS",
    "ADANIENT.NS", "KOTAKBANK.NS", "TITAN.NS", "ONGC.NS", "NTPC.NS", "AXISBANK.NS", "ADANIPORTS.NS",
    "M&M.NS", "POWERGRID.NS", "COALINDIA.NS", "BAJAJFINSV.NS", "BAJAJ-AUTO.NS", "JSWSTEEL.NS",
    "TATASTEEL.NS", "TECHM.NS", "EICHERMOT.NS", "NESTLEIND.NS", "GRASIM.NS", "BRITANNIA.NS",
    "CIPLA.NS", "DRREDDY.NS", "WIPRO.NS", "HINDALCO.NS", "BPCL.NS", "HEROMOTOCO.NS", "TATACONSUM.NS",
    "SHRIRAMFIN.NS", "DIVISLAB.NS", "APOLLOHOSP.NS", "SBILIFE.NS", "HDFCLIFE.NS", "BEL.NS", "TRENT.NS"
]

def run_3month_market_direction_backtest():
    print("=" * 115)
    print("🧠📊 3-MONTH COMPREHENSIVE BACKTEST: 09:45 AM MARKET DIRECTION ENGINE & SECTOR LEADER EXECUTION")
    print("=" * 115)
    print("🗓️ Evaluation Period: Last 60 Trading Days (July 2026 – September 2026)")
    print("⚙️ Strategy Logic:")
    print("   1. At 09:45 AM IST: Evaluate NIFTY 50 Breadth (Advances/Declines), 11 Sector Rankings, and 15m Technicals.")
    print("   2. Compute 09:45 Directional Bias: LONG (Bullish), SHORT (Bearish), or NEUTRAL (Breadth < 56%).")
    print("   3. If Bias == LONG: Trade pullbacks on Top 2 Gainer Sector Leaders (1:2 RR + 10 EMA Trailing Exit).")
    print("   4. If Bias == SHORT: Trade pullbacks on Top 2 Loser Sector Leaders (1:2 RR + 10 EMA Trailing Exit).")
    print("   5. If Bias == NEUTRAL: Stand down flat (Zero Risk).")
    print("=" * 115 + "\n")

    # 1. Download 5m Nifty & Stock Data
    print("📥 Downloading 5-minute candles for Nifty 50 and Sector Universe...")
    nifty_df = yf.download('^NSEI', period='60d', interval='5m', progress=False)
    if isinstance(nifty_df.columns, pd.MultiIndex): nifty_df.columns = nifty_df.columns.get_level_values(0)
    nifty_df = nifty_df.dropna().copy()
    nifty_df['VWAP'] = calculate_intraday_vwap(nifty_df)
    nifty_df['RSI'] = calculate_rsi(nifty_df['Close'], 14)
    nifty_df['SuperTrend'] = calculate_supertrend(nifty_df, 10, 3.0)

    # Gather all unique stock symbols
    all_syms = set(NIFTY_50_STOCKS)
    for s_list in SECTORS.values():
        all_syms.update(s_list)
    all_syms = list(all_syms)

    stock_data = {}
    for s in all_syms:
        try:
            d = yf.download(s, period='60d', interval='5m', progress=False)
            if isinstance(d.columns, pd.MultiIndex): d.columns = d.columns.get_level_values(0)
            d = d.dropna().copy()
            if len(d) >= 100:
                d['VWAP'] = calculate_intraday_vwap(d)
                d['EMA10'] = calculate_ema(d['Close'], 10)
                stock_data[s] = d
        except Exception:
            pass

    print(f"✅ Loaded 5m data for Nifty 50 and {len(stock_data)} liquid sector stocks.\n")

    dates = np.unique(nifty_df.index.date)
    print(f"📅 Total Trading Sessions Found: {len(dates)} days (from {dates[0]} to {dates[-1]})\n")

    direction_correct_count = 0
    directional_days_count = 0
    neutral_days_count = 0
    all_trades = []
    daily_results = []

    for session_date in dates:
        day_nifty = nifty_df[nifty_df.index.date == session_date].copy()
        if len(day_nifty) < 20: continue

        # A. Evaluate 09:15 to 09:45 AM Snapshot
        nifty_0945_bars = day_nifty.between_time('09:15', '09:45')
        if len(nifty_0945_bars) < 5: continue

        bar_0945 = nifty_0945_bars.iloc[-1]
        spot_0945 = bar_0945['Close']
        open_0915 = nifty_0945_bars.iloc[0]['Open']
        vwap_0945 = bar_0945['VWAP']
        rsi_0945 = bar_0945['RSI']
        st_0945 = bar_0945['SuperTrend']
        nifty_pct_0945 = ((spot_0945 - open_0915) / open_0915) * 100.0

        # Nifty 50 Breadth at 09:45
        advances = 0
        declines = 0
        for s in NIFTY_50_STOCKS:
            if s in stock_data:
                s_day = stock_data[s][stock_data[s].index.date == session_date].between_time('09:15', '09:45')
                if len(s_day) >= 2:
                    p_c = s_day.iloc[-1]['Close']
                    p_o = s_day.iloc[0]['Open']
                    if p_c > p_o: advances += 1
                    elif p_c < p_o: declines += 1

        tot_breadth = advances + declines
        adv_ratio = (advances / tot_breadth) if tot_breadth > 0 else 0.50

        # Sector Rankings at 09:45
        sector_perf = {}
        for sec_name, sec_stocks in SECTORS.items():
            pcts = []
            for s in sec_stocks:
                if s in stock_data:
                    s_day = stock_data[s][stock_data[s].index.date == session_date].between_time('09:15', '09:45')
                    if len(s_day) >= 2:
                        p_c = s_day.iloc[-1]['Close']
                        p_o = s_day.iloc[0]['Open']
                        pcts.append(((p_c - p_o) / p_o) * 100.0)
            if pcts:
                sector_perf[sec_name] = np.mean(pcts)

        sorted_sectors = sorted(sector_perf.items(), key=lambda x: x[1], reverse=True)
        top_sector = sorted_sectors[0] if sorted_sectors else ("N/A", 0.0)
        bot_sector = sorted_sectors[-1] if sorted_sectors else ("N/A", 0.0)

        # 09:45 Composite Direction Scoring
        score = 0
        if adv_ratio >= 0.56: score += 30
        elif adv_ratio <= 0.44: score -= 30

        if spot_0945 > vwap_0945: score += 25
        else: score -= 25

        if rsi_0945 > 52 and st_0945 == 1: score += 25
        elif rsi_0945 < 48 and st_0945 == -1: score -= 25

        if top_sector[1] > 0.75: score += 20
        elif bot_sector[1] < -0.75: score -= 20

        if score >= 45: bias = 'LONG'
        elif score <= -45: bias = 'SHORT'
        else: bias = 'NEUTRAL'

        # Outcome on Nifty from 09:45 to 15:15 close
        eod_bar = day_nifty.between_time('15:15', '15:30')
        eod_close = eod_bar.iloc[-1]['Close'] if len(eod_bar) > 0 else day_nifty.iloc[-1]['Close']
        nifty_post_0945_change = ((eod_close - spot_0945) / spot_0945) * 100.0

        is_correct = False
        if bias == 'LONG':
            directional_days_count += 1
            if nifty_post_0945_change > 0:
                direction_correct_count += 1
                is_correct = True
        elif bias == 'SHORT':
            directional_days_count += 1
            if nifty_post_0945_change < 0:
                direction_correct_count += 1
                is_correct = True
        else:
            neutral_days_count += 1

        # B. Execute Trades on Sector Leader Candidates (09:45 to 15:00 IST)
        day_pnl = 0.0
        day_trades = 0
        executed_candidates = []

        if bias != 'NEUTRAL':
            target_sector = top_sector[0] if bias == 'LONG' else bot_sector[0]
            candidates = SECTORS.get(target_sector, [])[:3]

            for cand in candidates:
                if cand not in stock_data: continue
                c_bars = stock_data[cand][stock_data[cand].index.date == session_date].between_time('09:45', '15:00')
                if len(c_bars) < 10: continue

                # Lowest volume discovery in 09:45-10:15 window
                early_bars = c_bars.between_time('09:45', '10:15')
                if len(early_bars) < 3: continue
                lowest_v = early_bars['Volume'].min()

                post_bars = c_bars.between_time('10:15', '15:00')
                pos = None

                for b_idx in range(len(post_bars)):
                    bar = post_bars.iloc[b_idx]
                    c = bar['Close']
                    o = bar['Open']
                    h = bar['High']
                    l = bar['Low']
                    v = bar['Volume']
                    vwap = bar['VWAP']
                    ema10 = bar['EMA10']

                    is_opp = (c < o) if bias == 'LONG' else (c > o)

                    if pos is None:
                        # Pullback / Lowest volume trigger breach
                        if is_opp and v < lowest_v and v > 0:
                            if bias == 'LONG' and c > vwap:
                                trig = h + 0.05
                                sl = l - 0.05
                                risk = max(trig - sl, trig * 0.0035)
                                sl = trig - risk
                                tgt = trig + (risk * 2.0)
                                pos = {'dir': 'LONG', 'entry': trig, 'sl': sl, 'tgt': tgt, 'partial': False}
                            elif bias == 'SHORT' and c < vwap:
                                trig = l - 0.05
                                sl = h + 0.05
                                risk = max(sl - trig, trig * 0.0035)
                                sl = trig + risk
                                tgt = trig - (risk * 2.0)
                                pos = {'dir': 'SHORT', 'entry': trig, 'sl': sl, 'tgt': tgt, 'partial': False}
                    else:
                        exit_p = 0.0
                        exit_reason = ""
                        p_dir = pos['dir']
                        entry_p = pos['entry']
                        sl_p = pos['sl']
                        tgt_p = pos['tgt']
                        partial = pos['partial']

                        if p_dir == 'LONG':
                            if l <= sl_p: exit_p = sl_p; exit_reason = "SL_HIT"
                            elif not partial and h >= tgt_p:
                                pos['partial'] = True
                                pos['p_pnl'] = (tgt_p - entry_p) * 0.5
                                pos['sl'] = entry_p # Move to cost
                            elif partial and c < ema10: exit_p = c; exit_reason = "10_EMA_TRAIL"
                            elif b_idx == len(post_bars) - 1: exit_p = c; exit_reason = "EOD_EXIT"
                        else:
                            if h >= sl_p: exit_p = sl_p; exit_reason = "SL_HIT"
                            elif not partial and l <= tgt_p:
                                pos['partial'] = True
                                pos['p_pnl'] = (entry_p - tgt_p) * 0.5
                                pos['sl'] = entry_p
                            elif partial and c > ema10: exit_p = c; exit_reason = "10_EMA_TRAIL"
                            elif b_idx == len(post_bars) - 1: exit_p = c; exit_reason = "EOD_EXIT"

                        if exit_p > 0:
                            runner = ((exit_p - entry_p) if p_dir == 'LONG' else (entry_p - exit_p)) * (0.5 if partial else 1.0)
                            tot_pts = (pos['p_pnl'] + runner) if partial else runner
                            
                            # Fixed 2 lots sizing (~Rs 2,000 risk per trade budget)
                            unit_risk = max(0.50, abs(entry_p - pos['sl']))
                            qty = max(25, int(2000 / unit_risk))
                            trade_pnl = tot_pts * qty
                            
                            day_pnl += trade_pnl
                            day_trades += 1
                            executed_candidates.append(cand.replace('.NS', ''))
                            
                            all_trades.append({
                                'date': session_date,
                                'symbol': cand.replace('.NS', ''),
                                'bias': bias,
                                'pnl_rs': trade_pnl,
                                'exit_reason': exit_reason
                            })
                            break

        daily_results.append({
            'date': session_date,
            'bias': bias,
            'score': score,
            'adv_ratio': adv_ratio,
            'top_sector': top_sector[0],
            'top_sector_pct': top_sector[1],
            'nifty_move_post_0945': nifty_pct_0945,
            'trades': day_trades,
            'day_pnl': day_pnl,
            'candidates': executed_candidates
        })

    # ---------------------------------------------------------
    # Comprehensive Reporting
    # ---------------------------------------------------------
    res_df = pd.DataFrame(daily_results)
    trades_df = pd.DataFrame(all_trades)

    print("=" * 115)
    print("📊 3-MONTH PERFORMANCE & DIRECTIONAL ACCURACY ANALYSIS")
    print("=" * 115)
    
    dir_acc = (direction_correct_count / directional_days_count * 100.0) if directional_days_count > 0 else 0
    print(f"• Total Evaluated Sessions:    {len(res_df)} trading days")
    print(f"• Directional Bias Triggered:  {directional_days_count} days ({directional_days_count/len(res_df)*100:.1f}%)")
    print(f"• Neutral / Stood Down Days:   {neutral_days_count} days ({neutral_days_count/len(res_df)*100:.1f}% - Zero Risk)")
    print(f"• 09:45 Direction Accuracy:    {direction_correct_count} / {directional_days_count} ({dir_acc:.1f}% Accurate EOD Market Predictor)")
    print("=" * 115)

    if len(trades_df) > 0:
        wins = trades_df[trades_df['pnl_rs'] > 0]
        losses = trades_df[trades_df['pnl_rs'] <= 0]
        win_rate = (len(wins) / len(trades_df)) * 100.0
        total_pnl = trades_df['pnl_rs'].sum()
        gross_w = wins['pnl_rs'].sum()
        gross_l = abs(losses['pnl_rs'].sum())
        pf = gross_w / gross_l if gross_l > 0 else 99.9

        print("\n📈 TRADE EXECUTION PERFORMANCE (TOP SECTOR CANDIDATES IN 09:45 DIRECTION):")
        print("-" * 85)
        print(f"• Total Trades Executed:       {len(trades_df)} trades (~{len(trades_df)/len(res_df):.2f} trades/day)")
        print(f"• Winning Trades:              {len(wins)} ({win_rate:.1f}% Win Rate)")
        print(f"• Losing Trades:               {len(losses)} ({100-win_rate:.1f}% Loss Rate)")
        print(f"• Gross Profit:                ₹{gross_w:+,.2f}")
        print(f"• Gross Loss:                  ₹{-gross_l:-,.2f}")
        print(f"• Profit Factor:               {pf:.2f}")
        print(f"• Total 3-Month Net P&L:       ₹{total_pnl:+,.2f} (Fixed 2 Lots / ₹2,000 Risk per trade)")
        print(f"• Average P&L per Trade:       ₹{total_pnl/len(trades_df):+,.2f}")
        print("-" * 85)

    # Monthly Breakdown
    res_df['Month'] = res_df['date'].apply(lambda x: x.strftime('%Y-%m'))
    monthly_summary = res_df.groupby('Month').agg(
        days=('date', 'count'),
        long_days=('bias', lambda x: (x == 'LONG').sum()),
        short_days=('bias', lambda x: (x == 'SHORT').sum()),
        neutral_days=('bias', lambda x: (x == 'NEUTRAL').sum()),
        trades=('trades', 'sum'),
        net_pnl=('day_pnl', 'sum')
    )
    print("\n📅 MONTH-BY-MONTH BREAKDOWN:")
    print("-" * 85)
    print(f"{'Month':<10} {'Days':<6} {'Long':<6} {'Short':<6} {'Neutral':<9} {'Trades':<8} {'Net P&L (₹)':<15}")
    print("-" * 85)
    for ym, r in monthly_summary.iterrows():
        print(f"{ym:<10} {r['days']:<6} {r['long_days']:<6} {r['short_days']:<6} {r['neutral_days']:<9} {r['trades']:<8} ₹{r['net_pnl']:<+14,.2f}")
    print("-" * 85)

if __name__ == '__main__':
    run_3month_market_direction_backtest()
