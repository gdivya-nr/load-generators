using LoadGen.Oltp.Services;
using Microsoft.AspNetCore.Mvc;
using NewRelic.Api.Agent;
using NR = NewRelic.Api.Agent.NewRelic;

namespace LoadGen.Oltp.Controllers;

[ApiController]
[Route("api/transactions")]
public class TransactionController : ControllerBase
{
    private readonly TransactionService _transactionService;
    private readonly OrderService _orderService;
    private readonly ILogger<TransactionController> _logger;

    public TransactionController(
        TransactionService transactionService,
        OrderService orderService,
        ILogger<TransactionController> logger)
    {
        _transactionService = transactionService;
        _orderService = orderService;
        _logger = logger;
    }

    [HttpPost("process")]
    public IActionResult ProcessPayment([FromQuery] long orderId)
    {
        try
        {
            NR.GetAgent().CurrentTransaction.AddCustomAttribute("orderId", orderId);
            bool success = _transactionService.ProcessPayment(orderId);
            _orderService.UpdateOrderStatus(orderId, success ? "COMPLETED" : "PAYMENT_FAILED");
            return Ok(new { orderId, success, status = success ? "COMPLETED" : "PAYMENT_FAILED" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error processing payment");
            NR.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }
}
