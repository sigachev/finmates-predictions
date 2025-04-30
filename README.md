# Uniswap V3 Price Predictor

A sophisticated Spring Boot application for predicting price ranges, optimizing liquidity positions, and calculating potential profitability for Uniswap V3 pools.

## Overview

This application uses advanced machine learning techniques including Bayesian modeling and Monte Carlo simulations to predict cryptocurrency price ranges and help liquidity providers optimize their positions on Uniswap V3. By analyzing historical data and current market conditions, it provides predictions with customizable confidence intervals and time horizons.

## Key Features

### Price Prediction
- Predict price ranges for any Uniswap V3 pool with configurable confidence intervals
- Support for both short-term (hours) and long-term (up to 30 days) forecasting
- Advanced Monte Carlo simulations for improved accuracy in long-term predictions
- Mean reversion modeling for realistic extended forecasts

### Position Optimization
- Calculate optimal tick ranges for liquidity provision
- Estimate fees, impermanent loss, and overall position profitability
- Generate time-based profit projections for different position durations
- Analyze historical price movements to maximize range utilization

### Data Analysis
- Collect and analyze historical pool data from both on-chain sources and The Graph
- Configurable training on variable amounts of historical data (Y days parameter)
- Real-time data collection with resilient fallback mechanisms
- Proper handling of token decimals and fee tiers for accurate calculations

## Technical Stack

- **Framework**: Spring Boot
- **Machine Learning**: DeepLearning4J
- **Blockchain Integration**: Web3j
- **Data Sources**: Arbitrum RPC, The Graph API
- **Statistical Methods**: Bayesian modeling, Monte Carlo simulation, volatility scaling

## Configuration

Key configuration parameters in `application.properties`:

```properties
# ML model configuration
model.training.days=30             # Historical days for model training (Y parameter)
model.retraining.schedule.hours=24 # Automatic model retraining interval
model.epochs=10                    # Training epochs
model.sequence.length=48           # Sequence length for predictions
model.mc.samples=100               # Monte Carlo simulation samples

# Blockchain configuration
arbitrum.rpc=https://arb1.arbitrum.io/rpc
uniswap.default.pool=0x641C00A822e8b671738d32a431a4Fb6074E5c79d
uniswap.default.fee=500