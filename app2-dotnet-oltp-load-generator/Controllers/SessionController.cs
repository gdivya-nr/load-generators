using LoadGen.Oltp.Services;
using Microsoft.AspNetCore.Mvc;
using NewRelic.Api.Agent;
using NR = NewRelic.Api.Agent.NewRelic;

namespace LoadGen.Oltp.Controllers;

[ApiController]
[Route("api/sessions")]
public class SessionController : ControllerBase
{
    private readonly SessionService _sessionService;
    private readonly ILogger<SessionController> _logger;

    public SessionController(SessionService sessionService, ILogger<SessionController> logger)
    {
        _sessionService = sessionService;
        _logger = logger;
    }

    [HttpPost("create")]
    public IActionResult CreateSession([FromQuery] long customerId)
    {
        try
        {
            NR.GetAgent().CurrentTransaction.AddCustomAttribute("customerId", customerId);
            string sessionId = _sessionService.CreateSession(customerId);
            _sessionService.UpdateSessionActivity(sessionId);
            return Ok(new { sessionId, customerId, status = "ACTIVE" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error creating session");
            NR.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }

    [HttpDelete("expire")]
    public IActionResult ExpireSessions()
    {
        try
        {
            _sessionService.ExpireOldSessions();
            _sessionService.DeleteExpiredSessions();
            return Ok(new { status = "EXPIRED" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error expiring sessions");
            NR.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }
}
