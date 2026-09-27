using LoadGen.Oltp.Services;
using Microsoft.AspNetCore.Mvc;
using NewRelic.Api.Agent;
using NR = NewRelic.Api.Agent.NewRelic;

namespace LoadGen.Oltp.Controllers;

[ApiController]
[Route("api/inventory")]
public class InventoryController : ControllerBase
{
    private readonly InventoryService _inventoryService;
    private readonly ILogger<InventoryController> _logger;

    public InventoryController(InventoryService inventoryService, ILogger<InventoryController> logger)
    {
        _inventoryService = inventoryService;
        _logger = logger;
    }

    [HttpGet("{productId}/check")]
    public IActionResult CheckInventory(long productId)
    {
        try
        {
            NR.GetAgent().CurrentTransaction.AddCustomAttribute("productId", productId);
            int available = _inventoryService.CheckAvailability(productId);
            if (available < 100)
            {
                int restockAmount = new Random().Next(500) + 100;
                _inventoryService.RestockInventory(productId, restockAmount);
                available += restockAmount;
            }
            _inventoryService.UpdateWarehouseLocation(productId);
            return Ok(new { productId, available, status = "SUCCESS" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error checking inventory");
            NR.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }

    [HttpPut("{productId}/restock")]
    public IActionResult RestockInventory(long productId, [FromQuery] int quantity)
    {
        try
        {
            var txn = NR.GetAgent().CurrentTransaction;
            txn.AddCustomAttribute("productId", productId);
            txn.AddCustomAttribute("quantity", quantity);
            _inventoryService.RestockInventory(productId, quantity);
            return Ok(new { productId, restocked = quantity, status = "SUCCESS" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error restocking inventory");
            NR.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }

    [HttpPut("bulk-update")]
    public IActionResult BulkUpdateInventory()
    {
        try
        {
            _inventoryService.BulkUpdateInventory();
            return Ok(new { status = "SUCCESS", updated = true });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error bulk updating inventory");
            NR.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }
}
