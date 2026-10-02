package com.tradingbot.strategy.condor.model;

/**
 * State lifecycle of the Monthly Asymmetric Put Condor Strategy.
 */
public enum PutCondorState {
    /** Waiting for the 1st trading day of the monthly cycle */
    IDLE,

    /** Active base 4-leg Put Condor deployed and monitored */
    CONDOR_ACTIVE,

    /** Adjustment A active: 100-pt Bull Put Spread added on upside rally to fund debit */
    UPSIDE_FINANCED,

    /** Adjustment B active: Leg 1 (K1) rolled down 100 pts on downside pullback into trough */
    SWEET_SPOT_LOCK,

    /** Position exited / squared off (Target hit, Deep Crash defense, or Expiry) */
    SQUARED_OFF
}
