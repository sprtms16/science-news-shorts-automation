param (
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ChannelId = "science",
    [int]$IntervalSeconds = 300
)

Write-Host "🚀 Paced Video Re-generation Runner Started" -ForegroundColor Cyan
Write-Host "Config: Channel=$ChannelId, Interval=$($IntervalSeconds)s, URL=$BaseUrl" -ForegroundColor Gray

$phase49DeployTime = "2026-02-13 22:48"
Write-Host "Targeting videos created before $phase49DeployTime" -ForegroundColor Yellow
Write-Host "----------------------------------------------------"

while ($true) {
    Try {
        $url = "$BaseUrl/admin/videos/regeneration/paced-trigger?channelId=$ChannelId"
        Write-Host "[$(Get-Date -Format 'HH:mm:ss')] Triggering re-generation for $ChannelId..." -NoNewline
        
        $response = Invoke-RestMethod -Method Post -Uri $url -ContentType "application/json"
        
        if ($response.message -like "*No more old videos*") {
            Write-Host " `n✅ Completed: $($response.message)" -ForegroundColor Green
            Break
        }
        else {
            Write-Host " `n✅ Success: $($response.message)" -ForegroundColor Green
            if ($response.remainingCount -ne $null) {
                Write-Host "   📦 Remaining: $($response.remainingCount) videos" -ForegroundColor Gray
            }
        }
    }
    Catch {
        Write-Host " `n❌ Error: $($_.Exception.Message)" -ForegroundColor Red
    }

    Write-Host "⏳ Waiting $($IntervalSeconds) seconds for next trigger..." -ForegroundColor Gray
    Start-Sleep -Seconds $IntervalSeconds
}

Write-Host "🏁 Finished paced re-generation for $ChannelId." -ForegroundColor Cyan
