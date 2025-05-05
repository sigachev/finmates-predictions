# Uniswap V3 Price Predictor

A Spring Boot application that uses machine learning to predict price ranges for Uniswap V3 liquidity pools. This tool helps liquidity providers optimize their positions by predicting price movements with statistical confidence.

## Features

- **Bayesian Price Prediction**: Predicts price ranges with uncertainty quantification
- **Optimal Tick Range Calculation**: Suggests ideal tick ranges for Uniswap V3 positions
- **Fee Estimation**: Estimates potential fees for a given price range
- **Impermanent Loss Prediction**: Forecasts potential impermanent loss based on predicted price movements
- **REST API**: Provides endpoints for predictions, status monitoring, and model retraining
- **Automatic Model Retraining**: Scheduled retraining to incorporate new market data

## Architecture

The system consists of several key components:

- **Neural Network Model**: Uses LSTM layers with Bayesian dropout for uncertainty estimation
- **Blockchain Integration**: Connects to Ethereum/Arbitrum nodes to fetch on-chain data
- **The Graph Integration**: Retrieves historical price and liquidity data via GraphQL
- **Prediction Pipeline**: Coordinates data collection, model inference, and result formatting

## Prerequisites

- JDK 17 or higher
- Maven 3.8+
- An Ethereum/Arbitrum RPC endpoint
- A Graph API key for accessing Uniswap subgraphs

## Configuration

Key configuration properties in `application.properties`:

```properties
# The Graph API key
thegraph.api.key=your_graph_api_key

# Arbitrum RPC URL with fallback options
arbitrum.rpc=https://arb1.arbitrum.io/rpc
arbitrum.rpc.backup1=https://arbitrum-one.public.blastapi.io
arbitrum.rpc.backup2=https://rpc.ankr.com/arbitrum

# Default pool configuration
uniswap.default.pool=0x641C00A822e8b671738d32a431a4Fb6074E5c79d

# ML model configuration
model.retraining.schedule.hours=24
model.historical.days=30
model.learning.rate=0.0005
model.dropout.rate=0.3
model.epochs=100
```

## Building the Project

```bash
mvn clean package
```

## Running the Application

```bash
java -jar target/uniswap-predictor-1.0.0.jar
```

## API Endpoints

### Predict Price Range

**Endpoint**: `POST /api/v1/prediction/price-range`

**Request Body**:
```json
{
  "poolAddress": "0x641C00A822e8b671738d32a431a4Fb6074E5c79d",
  "confidenceLevel": 0.95,
  "timePeriodHours": 24
}
```

**Response**:
```json
{
  "lowerPriceRange": 1950.25,
  "upperPriceRange": 2150.75,
  "optimalLowerTick": -203740,
  "optimalUpperTick": -201600,
  "predictedFees": 0.85,
  "confidenceLevel": 0.95,
  "poolAddress": "0x641C00A822e8b671738d32a431a4Fb6074E5c79d",
  "timestamp": 1683532800000,
  "currentPrice": 2050.0,
  "predictedImpermanentLoss": 1.2,
  "predictionPeriodHours": 24,
  "predictionEndTime": "2023-05-09T12:00:00Z"
}
```

### Alternative GET Endpoint

**Endpoint**: `GET /api/v1/prediction/price-range/{poolAddress}?confidenceLevel=0.95&timePeriodHours=24`

### Get Model Status

**Endpoint**: `GET /api/v1/prediction/status`

**Response**:
```
Model ready for predictions for pool: 0x641C00A822e8b671738d32a431a4Fb6074E5c79d
```

### Get Pool-Specific Model Status

**Endpoint**: `GET /api/v1/prediction/status/{poolAddress}`

### Trigger Model Retraining

**Endpoint**: `POST /api/v1/prediction/retrain?poolAddress={poolAddress}`

### Get Training Progress

**Endpoint**: `GET /api/v1/prediction/progress/{poolAddress}`

**Response**:
```json
{
  "poolAddress": "0x641C00A822e8b671738d32a431a4Fb6074E5c79d",
  "token0Symbol": "WETH",
  "token1Symbol": "USDC",
  "progressPercentage": 80,
  "inProgress": true,
  "modelReady": false,
  "currentEpoch": 8,
  "totalEpochs": 10,
  "latestScore": 0.0023,
  "lastUpdateTime": 1683532700000
}
```

## Advanced Configuration

### Memory Optimization

```properties
spring.jvm.memory=-Xmx4g
dl4j.dtype=HALF
```

### Hyperparameter Tuning

```properties
model.sequence.length=48
model.hidden.size=128
model.l2.regularization=0.00001
model.mini.batch.size=32
```

### Performance Tuning

```properties
executor.core.pool.size=5
executor.max.pool.size=10
executor.queue.capacity=25
```

## Model Architecture

The neural network architecture consists of:

1. Multiple LSTM layers for capturing sequential price movements
2. Dropout layers for Bayesian uncertainty estimation
3. Dense layers for feature combination and dimensionality reduction
4. Batch normalization for training stability
5. Output layer providing mean and standard deviation predictions

## Development

### Project Structure

```
src/
├── main/
│   ├── java/
│   │   └── com/
│   │       └── uniswap/
│   │           └── predictor/
│   │               ├── config/        # Application configuration
│   │               ├── controller/    # REST API endpoints
│   │               ├── dto/           # Data transfer objects
│   │               ├── model/         # ML models
│   │               └── service/       # Business logic
│   └── resources/
│       └── application.properties     # Configuration properties
```

### Adding Support for New Pools

The system can dynamically train models for different Uniswap V3 pools. To add a new pool:

1. Call the retraining endpoint with the new pool address
2. Monitor training progress via the progress endpoint
3. Once training is complete, predictions can be made for the new pool

## Troubleshooting

### Common Issues

1. **RPC Connection Errors**: Configure backup RPCs in application.properties
2. **GraphQL API Limits**: Check your API usage and consider upgrading your plan
3. **Out of Memory Errors**: Increase JVM heap size or reduce model complexity
4. **Training Failures**: Check logs for details on data collection issues

### Logs

Logs are stored in `logs/uniswap-predictor.log` by default.

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Acknowledgments

- Built with [Spring Boot](https://spring.io/projects/spring-boot)
- Machine learning powered by [DeepLearning4J](https://deeplearning4j.konduit.ai/)
- Blockchain interaction via [Web3j](https://docs.web3j.io/)
- Historical data provided by [The Graph](https://thegraph.com/)