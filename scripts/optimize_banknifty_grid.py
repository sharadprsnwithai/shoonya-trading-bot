import itertools
import pandas as pd
import yfinance as yf
from backtest_banknifty_monthly import (
    calculate_supertrend,
    calculate_adx,
    calculate_intraday_vwap,
    calculate_rsi,
    run_banknifty_backtest
)

def main():
    ticker = "^NSEBANK"
    print(f"Fetching last 60 days 5-minute data for BANK NIFTY ({ticker})...")
    df_5m = yf.download(ticker, period="60d", interval="5m", progress=False)
    if isinstance(df_5m.columns, pd.MultiIndex):
        df_5m.columns = df_5m.columns.get_level_values(0)

    if df_5m.index.tz is None:
        df_5m.index = df_5m.index.tz_localize('UTC').tz_convert('Asia/Kolkata')
    else:
        df_5m.index = df_5m.index.tz_convert('Asia/Kolkata')

    df_5m = df_5m.between_time('09:15', '15:30')
    df_5m['RSI_5m'] = calculate_rsi(df_5m['Close'], 14)
    df_5m = calculate_intraday_vwap(df_5m)

    df_15m = df_5m.resample('15min', closed='left', label='left').agg({
        'Open': 'first',
        'High': 'max',
        'Low': 'min',
        'Close': 'last',
        'Volume': 'sum'
    }).dropna()

    df_15m = calculate_supertrend(df_15m, period=10, multiplier=2.0)
    df_15m = calculate_adx(df_15m, period=14)
    df_15m['RSI_15m'] = calculate_rsi(df_15m['Close'], 14)

    df_5m['Time_15m'] = df_5m.index.floor('15min')
    cols_15m = ['SuperTrend', 'ST_Direction', 'ADX', 'Plus_DI', 'Minus_DI', 'RSI_15m']
    df_15m_subset = df_15m[cols_15m].shift(1)
    df_merged = df_5m.join(df_15m_subset, on='Time_15m', rsuffix='_15m')
    df_merged = df_merged.dropna(subset=['SuperTrend', 'VWAP', 'ADX', 'RSI_5m', 'RSI_15m'])

    # Grid parameters for Bank Nifty monthly options
    adx_vals = [20.0, 22.0, 25.0, 28.0]
    vwap_dists = [60.0, 80.0, 100.0, 120.0]
    sl_vals = [50.0, 65.0, 80.0]
    tp_vals = [100.0, 130.0, 160.0]
    trail_configs = [
        (25.0, 5.0, 55.0, 30.0),
        (30.0, 6.0, 65.0, 35.0),
        (35.0, 8.0, 75.0, 45.0)
    ]
    max_trades_opts = [1, 2]

    results = []

    for adx, vwap_d, sl, tp, trail, m_trd in itertools.product(
        adx_vals, vwap_dists, sl_vals, tp_vals, trail_configs, max_trades_opts
    ):
        t1, l1, t2, l2 = trail
        res = run_banknifty_backtest(
            df_merged,
            lot_size=30,
            num_lots=10,
            sl_pts=sl,
            tp_pts=tp,
            trail_trigger1=t1,
            trail_lock1=l1,
            trail_trigger2=t2,
            trail_lock2=l2,
            max_vwap_dist=vwap_d,
            adx_min=adx,
            max_trades_per_day=m_trd
        )
        if res['trades'] >= 10:
            results.append({
                'ADX': adx,
                'VWAP_Dist': vwap_d,
                'SL': sl,
                'TP': tp,
                'Trail': f"{t1:.0f}/{t2:.0f}",
                'MaxTrd': m_trd,
                'Trades': res['trades'],
                'WinRate': res['win_rate'],
                'NetPnL': res['pnl'],
                'PF': res['profit_factor'],
                'MaxDD': res['max_dd']
            })

    df_grid = pd.DataFrame(results)
    df_grid = df_grid.sort_values(by='NetPnL', ascending=False)

    print("\n" + "="*115)
    print("TOP 15 PARAMETER COMBINATIONS FOR BANK NIFTY MONTHLY EXPIRY (10 LOTS = 300 QTY)")
    print("="*115)
    print(f"{'Rank':<4} | {'ADX':<4} | {'VWAP':<5} | {'SL':<4} | {'TP':<5} | {'Trail':<7} | {'Trd/D':<5} | {'Trades':<6} | {'Win%':<6} | {'Net PnL (Rs)':<14} | {'PF':<5} | {'Max DD (Rs)':<12}")
    print("-"*115)
    for rank, (_, row) in enumerate(df_grid.head(15).iterrows(), 1):
        print(f"{rank:<4} | {row['ADX']:<4.0f} | {row['VWAP_Dist']:<5.0f} | {row['SL']:<4.0f} | {row['TP']:<5.0f} | {row['Trail']:<7} | {row['MaxTrd']:<5} | {row['Trades']:<6} | {row['WinRate']:<5.1f}% | Rs {row['NetPnL']:+11,.0f} | {row['PF']:<5.2f} | Rs {row['MaxDD']:+10,.0f}")
    print("="*115 + "\n")

if __name__ == '__main__':
    main()
