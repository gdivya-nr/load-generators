using NewRelic.Api.Agent;
using NR = NewRelic.Api.Agent.NewRelic;

namespace LoadGen.Oltp;

public class OltpLoadGenerator : IHostedService
{
    private readonly IHttpClientFactory _httpClientFactory;
    private readonly DatabaseManager _dbManager;
    private readonly int _numThreads;
    private readonly string _apiBaseUrl;
    private readonly ILogger<OltpLoadGenerator> _logger;
    private volatile bool _running;
    private readonly List<Task> _workers = new();
    private readonly CancellationTokenSource _cts = new();
    private readonly Random _random = new();

    public OltpLoadGenerator(
        IHttpClientFactory httpClientFactory,
        DatabaseManager dbManager,
        IConfiguration config,
        ILogger<OltpLoadGenerator> logger)
    {
        _httpClientFactory = httpClientFactory;
        _dbManager = dbManager;
        _numThreads = config.GetValue("LoadGenerator:Threads", 3);
        _apiBaseUrl = config["LoadGenerator:ApiBaseUrl"] ?? "http://localhost:8081";
        _logger = logger;
        _logger.LogInformation("OltpLoadGenerator initialized with {Threads} threads. API: {Url}", _numThreads, _apiBaseUrl);
    }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        _running = true;
        _logger.LogInformation(new string('=', 80));
        _logger.LogInformation("Starting OLTP load generation...");
        _logger.LogInformation(new string('=', 80));

        for (int i = 0; i < _numThreads; i++)
        {
            int threadId = i;
            _workers.Add(Task.Run(() => RunWorker(threadId, _cts.Token), _cts.Token));
        }

        _ = Task.Run(async () =>
        {
            while (_running && !_cts.Token.IsCancellationRequested)
            {
                try { await Task.Delay(10000, _cts.Token); } catch (OperationCanceledException) { break; }
                LogStatistics();
            }
        }, _cts.Token);

        return Task.CompletedTask;
    }

    public async Task StopAsync(CancellationToken cancellationToken)
    {
        _logger.LogInformation("Shutdown signal received, stopping load generator...");
        _running = false;
        _cts.Cancel();
        try { await Task.WhenAll(_workers).WaitAsync(TimeSpan.FromSeconds(30)); }
        catch (OperationCanceledException) { }
        _logger.LogInformation("OltpLoadGenerator shutdown complete.");
    }

    private async Task RunWorker(int threadId, CancellationToken ct)
    {
        _logger.LogInformation("Worker thread {ThreadId} started", threadId);
        int operationCount = 0;
        long lastBreakTime = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        int cycleOperations = 0;
        int consecutiveErrors = 0;

        while (_running && !ct.IsCancellationRequested)
        {
            try
            {
                long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                int breakInterval = 120000 + _random.Next(60000); // 2–3 min
                if (now - lastBreakTime > breakInterval)
                {
                    _logger.LogInformation("Worker {ThreadId} taking 5-second break after {Ops} operations", threadId, cycleOperations);
                    await Task.Delay(5000, ct);
                    lastBreakTime = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                    cycleOperations = 0;
                }

                int operation = _random.Next(100);
                if      (operation < 30) await CreateOrderWorkflow();
                else if (operation < 55) await UpdateCustomerWorkflow();
                else if (operation < 75) await InventoryCheckWorkflow();
                else if (operation < 90) await ProcessTransactionWorkflow();
                else if (operation < 95) await SessionManagementWorkflow();
                else if (operation < 98) await DeleteOldDataWorkflow();
                else if (operation < 99) await BulkInsertWorkflow();
                else                     await ProductOperationsWorkflow();

                operationCount++;
                cycleOperations++;
                consecutiveErrors = 0;
                await Task.Delay(_random.Next(50) + 30, ct); // 30–80ms
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                consecutiveErrors++;
                _logger.LogError(ex, "Error in worker {ThreadId} (consecutive errors: {Count})", threadId, consecutiveErrors);
                NR.NoticeError(ex);
                int backoffMs = Math.Min(1000 * (1 << (consecutiveErrors - 1)), 8000);
                try { await Task.Delay(backoffMs, ct); } catch (OperationCanceledException) { break; }
            }
        }

        _logger.LogInformation("Worker {ThreadId} completed {Ops} operations", threadId, operationCount);
    }

    [Transaction]
    private async Task CreateOrderWorkflow()
    {
        long customerId = _random.Next(1000) + 1;
        int numItems = _random.Next(5) + 1;
        var client = _httpClientFactory.CreateClient();
        await client.PostAsync($"{_apiBaseUrl}/api/orders/create?customerId={customerId}&numItems={numItems}", null);
    }

    [Transaction]
    private async Task UpdateCustomerWorkflow()
    {
        long customerId = _random.Next(1000) + 1;
        int points = _random.Next(100);
        var client = _httpClientFactory.CreateClient();
        await client.PutAsync($"{_apiBaseUrl}/api/customers/{customerId}/loyalty?points={points}", null);
    }

    [Transaction]
    private async Task InventoryCheckWorkflow()
    {
        long productId = _random.Next(500) + 1;
        var client = _httpClientFactory.CreateClient();
        await client.GetAsync($"{_apiBaseUrl}/api/inventory/{productId}/check");
    }

    [Transaction]
    private async Task ProcessTransactionWorkflow()
    {
        long orderId = _random.Next(1000) + 1;
        var client = _httpClientFactory.CreateClient();
        await client.PostAsync($"{_apiBaseUrl}/api/transactions/process?orderId={orderId}", null);
    }

    [Transaction]
    private async Task SessionManagementWorkflow()
    {
        long customerId = _random.Next(1000) + 1;
        var client = _httpClientFactory.CreateClient();
        await client.PostAsync($"{_apiBaseUrl}/api/sessions/create?customerId={customerId}", null);
    }

    [Transaction]
    private async Task DeleteOldDataWorkflow()
    {
        var client = _httpClientFactory.CreateClient();
        await client.DeleteAsync($"{_apiBaseUrl}/api/orders/old?daysToKeep=30");
    }

    [Transaction]
    private async Task BulkInsertWorkflow()
    {
        int batchSize = _random.Next(2) + 2; // 2–3
        var client = _httpClientFactory.CreateClient();
        await client.PostAsync($"{_apiBaseUrl}/api/orders/bulk?batchSize={batchSize}", null);
    }

    [Transaction]
    private async Task ProductOperationsWorkflow()
    {
        long productId = _random.Next(500) + 1;
        var client = _httpClientFactory.CreateClient();
        await client.GetAsync($"{_apiBaseUrl}/api/products/{productId}");
    }

    private void LogStatistics()
    {
        try
        {
            _logger.LogInformation("Load generator running. Threads: {Threads}", _numThreads);
            NR.RecordMetric("Custom/Database/ActiveConnections", 0);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error logging statistics");
        }
    }
}
