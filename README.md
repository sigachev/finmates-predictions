# finmates-predictions

# Uniswap V3 Price Predictor

A Spring Boot application that predicts price ranges and optimal liquidity positions for Uniswap V3 pools using Bayesian machine learning.

## Overview

This service predicts price ranges for Uniswap V3 pools with specified confidence levels and time periods. It dynamically calculates the optimal tick ranges for liquidity provision based on properly adjusted token decimals and fee tiers.

## Key Features

- **Dynamic Token Decimal Handling**: Properly fetches and handles token decimals for accurate price calculations
- **Fee Tier Detection**: Determines the correct tick spacing based on the pool's fee tier
- **Bayesian Price Prediction**: Uses a Bayesian neural network to predict price ranges with uncertainty quantification
- **Optimal Tick Calculation**: Calculates optimal tick ranges for liquidity provision
- **Fee and Impermanent Loss Estimation**: Estimates fees and potential impermanent loss for provided liquidity
- **Historical Data Analysis**: Collects and analyzes historical swap and liquidity events
- **Asynchronous Training**: Trains models asynchronously without blocking API responses
- **Resilient Blockchain Connectivity**: Multiple RPC endpoints with fallback mechanisms

## Components

### Core Services

1. **BlockchainService**: Interacts with the Ethereum blockchain to fetch on-chain data
    - Correctly handles token decimal differences for accurate price calculations
    - Dynamically detects fee tiers and corresponding tick spacings
    - Implements robust error handling and retry mechanisms

2. **GraphQLService**: Retrieves historical data from The Graph subgraphs
    - Fetches swap events, liquidity events, volume, fees, and volatility metrics

3. **DataCollectionService**: Combines blockchain and subgraph data
    - Processes historical events to create comprehensive data points
    - Calculates metrics like volatility, fees, and volume

4. **PredictionService**: Manages price prediction models
    - Trains Bayesian neural networks to predict price ranges
    - Estimates fees and impermanent loss
    - Calculates optimal liquidity positions

### Key Fixes Implemented

- **Token Decimal Handling**: Now dynamically fetches token decimals from ERC20 contracts instead of using hardcoded values
- **Support for Various Token Types**: Added fallback mechanisms for non-standard ERC20 token implementations
- **Improved Price Calculations**: Properly adjusts prices based on token decimal differences
- **Dynamic Fee Tier Detection**: Determines the correct tick spacing based on the pool's fee tier
- **Enhanced Error Handling**: Added retry logic and fallback mechanisms for blockchain RPC calls
- **Stablecoin Detection**: Special handling for known stablecoin addresses when direct decimal retrieval fails
- **Improved Logging**: Comprehensive logging for easier debugging and monitoring
- **HTTP Client Optimization**: Configured timeout and connection pooling for improved resilience

## API Endpoints

### Prediction Endpoints

- `GET /api/v1/prediction/price-range/{poolAddress}?confidenceLevel=0.95&timePeriodHours=24` - Get price range prediction
- `POST /api/v1/prediction/price-range` - Get price range prediction (request body)
- `GET /api/v1/prediction/status` - Get status of the default model
- `GET /api/v1/prediction/status/{poolAddress}` - Get status of a specific pool model
- `POST /api/v1/prediction/retrain` - Trigger model retraining
- `GET /api/v1/prediction/progress/{poolAddress}` - Get detailed training progress

### Pool Endpoints

- `GET /api/v1/pools` - Get list of available (trained) pools
- `GET /api/v1/pools/{poolAddress}/info` - Get current information about a pool
- `POST /api/v1/pools/track` - Start tracking and training model for a new pool

## Configuration

Key configuration options in `application.properties`:

```properties
# Blockchain RPC endpoints
arbitrum.rpc=https://arb1.arbitrum.io/rpc
arbitrum.rpc.backup1=https://arbitrum-one.public.blastapi.io
arbitrum.rpc.backup2=https://rpc.ankr.com/arbitrum

# Default pool and fee
uniswap.default.pool=0x641C00A822e8b671738d32a431a4Fb6074E5c79d
uniswap.default.fee=500

# Model parameters
model.retraining.schedule.hours=24
model.historical.days=30
model.epochs=10
```

## Dependencies

- Spring Boot 3.x
- Web3j for Ethereum interaction
- Jackson for JSON processing
- Deeplearning4j for neural network implementation
- Apache HTTP Components for HTTP client
- Lombok for boilerplate reduction

## Running the Application

```bash
# Build the application
./mvnw clean package

# Run the application
java -jar target/uniswap-predictor-1.0.0.jar
```

## Docker Support

```bash
# Build Docker image
docker build -t uniswap-predictor .

# Run Docker container
docker run -p 8089:8089 uniswap-predictor
```