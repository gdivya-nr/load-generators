using LoadGen.Oltp;
using LoadGen.Oltp.Services;

// Load .env file if present
var envFile = Path.Combine(Directory.GetCurrentDirectory(), ".env");
if (File.Exists(envFile))
{
    foreach (var line in File.ReadAllLines(envFile))
    {
        var trimmed = line.Trim();
        if (string.IsNullOrEmpty(trimmed) || trimmed.StartsWith('#')) continue;
        var idx = trimmed.IndexOf('=');
        if (idx < 0) continue;
        var key = trimmed[..idx].Trim();
        var value = trimmed[(idx + 1)..].Trim();
        Environment.SetEnvironmentVariable(key, value);
    }
}

var builder = WebApplication.CreateBuilder(args);

// Override appsettings with env vars
if (Environment.GetEnvironmentVariable("DB_CONNECTION_STRING") is { } dbCs)
    builder.Configuration["ConnectionStrings:DefaultConnection"] = dbCs;
if (Environment.GetEnvironmentVariable("DB_POOL_MAX") is { } poolMax)
    builder.Configuration["Database:MaxPoolSize"] = poolMax;
if (Environment.GetEnvironmentVariable("DB_POOL_MIN") is { } poolMin)
    builder.Configuration["Database:MinPoolSize"] = poolMin;
if (Environment.GetEnvironmentVariable("THREADS") is { } threads)
    builder.Configuration["LoadGenerator:Threads"] = threads;

// Services
builder.Services.AddSingleton<DatabaseManager>();
builder.Services.AddSingleton<CustomerService>();
builder.Services.AddSingleton<OrderService>();
builder.Services.AddSingleton<ProductService>();
builder.Services.AddSingleton<InventoryService>();
builder.Services.AddSingleton<TransactionService>();
builder.Services.AddSingleton<SessionService>();
builder.Services.AddSingleton<OltpLoadGenerator>();
builder.Services.AddHostedService(sp => sp.GetRequiredService<OltpLoadGenerator>());
builder.Services.AddHttpClient();
builder.Services.AddControllers();
builder.Services.AddHealthChecks();

var app = builder.Build();

app.MapControllers();
app.MapHealthChecks("/health");

Console.WriteLine(new string('=', 80));
Console.WriteLine(".NET OLTP Load Generator (APP2) Starting");
Console.WriteLine(new string('=', 80));

app.Run();
