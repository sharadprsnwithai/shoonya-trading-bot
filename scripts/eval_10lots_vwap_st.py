import datetime
import numpy as np
import pandas as pd
import yfinance as yf
from test_vwap_st_variations import calculate_supertrend, calculate_adx, calculate_intraday_vwap, backtest_engine

def run_comprehensive_eval():
    ticker = "^NSEI"
    df_5m = yf.download(ticker, period="60d", interval="5m", progress=False)
    if isinstance(df_5m.columns, pd.MultiIndex):
        df_5m.columns = df_5m.columns.get_level_values(0)

    if df_5m.index.tz is None:
        df_5m.index = df_5m.index.tz_localize('UTC').tz_convert('Asia/Kolkata')
    else:
        df_5m.index = df_5m.index.tz_convert('Asia/Kolkata')

    df_5m = df_5m.between_time('09:15', '15:30')
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

    df_5m['Time_15m'] = df_5m.index.floor('15min')
    cols_15m = ['SuperTrend', 'ST_Direction', 'ADX', 'Plus_DI', 'Minus_DI']
    df_15m_subset = df_15m[cols_15m].shift(1)
    df_merged = df_5m.join(df_15m_subset, on='Time_15m', rsuffix='_15m')
    df_merged = df_merged.dropna(subset=['SuperTrend', 'VWAP', 'ADX'])

    # Test with 10 lots (Portfolio sizing on Rs 10 Lakh capital)
    res_10lots = backtest_engine(df_merged, max_trades_per_day=2, st_multiplier=2.0, adx_thresh=20.0, sl_pts=35.0, tp_pts=50.0, exit_on_st_flip=True, exit_on_vwap=False)
    
    # Scale PnL for 10 lots
    scale = 10
    total_pnl_10l = res_10lots['pnl'] * scale
    gross_p = res_10lots['gross_profit'] * scale
    gross_l = res_10lots['gross_loss'] * scale
    max_dd = res_10lots['max_dd'] * scale

    print("================================================================================")
    print("NIFTY 15m VWAP + SUPERTREND (10, 2) + ADX(>=20) 10-LOT SIMULATION (60 DAYS)")
    print("================================================================================")
    print(f"Total Trades Taken   : {res_10lots['trades']}")
    print(f"Win Rate             : {res_10lots['win_rate']:.1f}%")
    print(f"Profit Factor        : {res_10lots['profit_factor']:.2f}")
    print(f"Gross Profit         : +Rs {gross_p:,.2f}")
    print(f"Gross Loss           : -Rs {gross_l:,.2f}")
    print(f"Net Realized PnL     : +Rs {total_pnl_10l:,.2f}")
    print(f"Max Drawdown         : Rs {max_dd:,.2f}")
    print("================================================================================")

if __name__ == '__main__':
    run_comprehensive_eval()
