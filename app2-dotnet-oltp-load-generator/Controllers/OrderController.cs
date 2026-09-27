using LoadGen.Oltp.Services;
using Microsoft.AspNetCore.Mvc;
using NewRelic.Api.Agent;
using NR = NewRelic.Api.Agent.NewRelic;

namespace LoadGen.Oltp.Controllers;

[ApiController]
[Route("api/orders")]
public class OrderController : ControllerBase
{
    private readonly OrderService _orderService;
    private readonly TransactionService _transactionService;
    private readonly InventoryService _inventoryService;
    private readonly ILogger<OrderController> _logger;

    public OrderController(
        OrderService orderService,
        TransactionService transactionService,
        InventoryService inventoryService,
        ILogger<OrderController> logger)
    {
        _orderService = orderService;
        _transactionService = transactionService;
        _inventoryService = inventoryService;
        _logger = logger;
    }

    [HttpPost("create")]
    public IActionResult CreateOrder([FromQuery] long customerId, [FromQuery] int numItems)
    {
        try
        {
            var txn = NR.GetAgent().CurrentTransaction;
            txn.AddCustomAttribute("customerId", customerId);
            txn.AddCustomAttribute("numItems", numItems);

            long orderId = _orderService.CreateOrder(customerId);

            var rng = new Random();
            for (int i = 0; i < numItems; i++)
            {
                long productId = rng.Next(500) + 1;
                int quantity = rng.Next(5) + 1;
                _orderService.AddOrderItem(orderId, productId, quantity);
            }

            _orderService.CalculateOrderTotal(orderId);
            _transactionService.CreateTransaction(orderId, "PAYMENT");
            _inventoryService.ReserveInventory(orderId);
            _orderService.LogAudit("ORDERS", orderId, "CREATE");

            return Ok(new { orderId, customerId, itemsAdded = numItems, status = "SUCCESS" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error creating order");
            NR.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message, status = "ERROR" });
        }
    }

    [HttpPut("{orderId}/status")]
    public IActionResult UpdateOrderStatus(long orderId, [FromQuery] string status)
    {
        try
        {
            var txn = NR.GetAgent().CurrentTransaction;
            txn.AddCustomAttribute("orderId", orderId);
            txn.AddCustomAttribute("status", status);
            _orderService.UpdateOrderStatus(orderId, status);
            return Ok(new { orderId, status, updated = true });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error updating order status");
            NR.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }

    [HttpDelete("old")]
    public IActionResult DeleteOldOrders([FromQuery] int daysToKeep = 30)
    {
        try
        {
            NR.GetAgent().CurrentTransaction.AddCustomAttribute("daysToKeep", daysToKeep);
            _orderService.DeleteOldCompletedOrders(daysToKeep);
            _orderService.DeleteCancelledOrders();
            _orderService.DeleteOldAuditLogs(7);
            return Ok(new { status = "DELETED", daysKept = daysToKeep });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error deleting old orders");
            NR.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }

    [HttpPost("bulk")]
    public IActionResult BulkCreateOrders([FromQuery] int batchSize)
    {
        try
        {
            NR.GetAgent().CurrentTransaction.AddCustomAttribute("batchSize", batchSize);
            var rng = new Random();
            int created = 0;
            for (int i = 0; i < batchSize; i++)
            {
                long customerId = rng.Next(1000) + 1;
                long orderId = _orderService.CreateOrder(customerId);
                int numItems = rng.Next(3) + 1;
                for (int j = 0; j < numItems; j++)
                {
                    long productId = rng.Next(500) + 1;
                    int quantity = rng.Next(3) + 1;
                    _orderService.AddOrderItem(orderId, productId, quantity);
                }
                _orderService.CalculateOrderTotal(orderId);
                created++;
            }
            _inventoryService.BulkUpdateInventory();
            return Ok(new { created, status = "SUCCESS" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error bulk creating orders");
            NR.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }
}
