using LoadGen.Oltp.Services;
using Microsoft.AspNetCore.Mvc;
using NewRelic.Api.Agent;
using NR = NewRelic.Api.Agent.NewRelic;

namespace LoadGen.Oltp.Controllers;

[ApiController]
[Route("api/customers")]
public class CustomerController : ControllerBase
{
    private readonly CustomerService _customerService;
    private readonly ILogger<CustomerController> _logger;

    public CustomerController(CustomerService customerService, ILogger<CustomerController> logger)
    {
        _customerService = customerService;
        _logger = logger;
    }

    [HttpPut("{customerId}/loyalty")]
    [Transaction(Web = true)]
    public IActionResult UpdateLoyaltyPoints(long customerId, [FromQuery] int points)
    {
        try
        {
            var txn = NR.GetAgent().CurrentTransaction;
            txn.AddCustomAttribute("customerId", customerId);
            txn.AddCustomAttribute("points", points);
            _customerService.UpdateLoyaltyPoints(customerId, points);
            return Ok(new { customerId, pointsAdded = points, status = "SUCCESS" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error updating loyalty points");
            NR.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }

    [HttpPut("{customerId}/upgrade")]
    [Transaction(Web = true)]
    public IActionResult UpgradeCustomerType(long customerId)
    {
        try
        {
            NR.GetAgent().CurrentTransaction.AddCustomAttribute("customerId", customerId);
            _customerService.UpgradeCustomerType(customerId);
            return Ok(new { customerId, upgraded = true, status = "SUCCESS" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error upgrading customer");
            NR.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }

    [HttpPost("{customerId}/access-log")]
    [Transaction(Web = true)]
    public IActionResult LogCustomerAccess(long customerId)
    {
        try
        {
            NR.GetAgent().CurrentTransaction.AddCustomAttribute("customerId", customerId);
            _customerService.LogCustomerAccess(customerId);
            return Ok(new { customerId, logged = true });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error logging customer access");
            NR.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }
}
