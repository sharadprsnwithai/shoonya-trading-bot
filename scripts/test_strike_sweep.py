import sys
import os
sys.path.insert(0, os.path.abspath('.'))
import math
import datetime
import numpy as np
import pandas as pd
import yfinance as yf
from collections import defaultdict
import scripts.compare_options_structures as cos

df_nifty = yf.download('^NSEI', interval='5m', period='60d', progress=False)
if isinstance(df_nifty.columns, pd.MultiIndex):
    df_nifty.columns = df_nifty.columns.get_level_values(0)
df_nifty.index = pd.to_datetime(df_nifty.index).tz_convert('Asia/Kolkata')

df_bees = yf.download('NIFTYBEES.NS', interval='5m', period='60d', progress=False)
if isinstance(df_bees.columns, pd.MultiIndex):
    df_bees.columns = df_bees.columns.get_level_values(0)
df_bees.index = pd.to_datetime(df_bees.index).tz_convert('Asia/Kolkata')

df_5m = df_nifty.copy()
if not df_bees.empty and 'Volume' in df_bees.columns:
    df_5m['Volume'] = df_bees['Volume'].reindex(df_5m.index).fillna(10000)
else:
    df_5m['Volume'] = 10000

all_dates = sorted(list(set(df_5m.index.date)))

print("=========================================================================================================")
print(" 🔬 STRIKE SELECTION SWEEP: OPTION SELLING FROM ATM TO 400 OTM (NIFTY 50)")
print("=========================================================================================================")
print(f"{'Option Strike':<22} | {'Trades':<6} | {'Win %':<6} | {'Profit Factor':<13} | {'Net P&L (₹)':<14} | {'Max DD (₹)':<12}")
print("-" * 90)

for offset in [0, 50, 100, 150, 200, 300, 400]:
    label = "ATM (0 pts)" if offset == 0 else f"{offset} pts OTM"
    trades, daily = cos.simulate_mode(df_5m, all_dates, f'{offset}_OTM_SELL', offset, 'SELL', 0.70, 0.60)
    if not trades: continue
    df_t = pd.DataFrame(trades)
    wins = df_t[df_t['is_win'] == True]
    losses = df_t[df_t['is_win'] == False]
    gp = wins['realized_pnl'].sum()
    gl = abs(losses['realized_pnl'].sum())
    pf = (gp / gl) if gl > 0 else float('inf')
    net = df_t['realized_pnl'].sum()
    wr = (len(wins) / len(df_t)) * 100.0
    pnl_series = pd.Series(list(daily.values()))
    cum = pnl_series.cumsum()
    dd = (cum.cummax() - cum).max() if not cum.empty else 0.0
    pnl_str = f"+₹{net:,.0f}" if net >= 0 else f"-₹{abs(net):,.0f}"
    print(f"SELL {label:<17} | {len(df_t):<6d} | {wr:<5.1f}% | {pf:<13.2f} | {pnl_str:<14} | ₹{dd:<11,.0f}")

print("=" * 90)
